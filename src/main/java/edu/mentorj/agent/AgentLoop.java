package edu.mentorj.agent;

import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.AgentResponse;
import edu.mentorj.api.AgentService;
import edu.mentorj.api.Citation;
import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.LlmService;
import edu.mentorj.api.StreamListener;
import edu.mentorj.api.Tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * AgentService implementation: retrieve chunks -> assemble prompt -> call the LLM -> tool loop -> assemble the answer.
 * Chunk sources become the answer citations directly; an empty retrieval sets generalKnowledgeOnly, and the one
 * chunk the relevance gate rejected is passed to the model as unverified context rather than discarded.
 */
public final class AgentLoop implements AgentService {

    private static final int TOP_K = 5;
    /**
     * Unverified context is one chunk, not five: it did not pass the relevance gate, so the budget spent on it and
     * the chance the model grounds an answer on an unrelated page both stay small.
     */
    private static final int LOW_CONFIDENCE_TOP_K = 1;
    private static final int MAX_TOOL_ROUNDS = 3;
    private static final int MAX_CALLS_PER_ROUND = 8;
    private static final int MAX_INPUT_CHARS = 60_000;
    private static final int MAX_CONTEXT_CHARS = 120_000;
    private static final StreamListener NO_STREAM = delta -> { };
    private static final Retrieved NO_RETRIEVAL = new Retrieved(List.of(), List.of());

    private final KnowledgeBase knowledgeBase;
    private final LlmService llmService;
    private final SystemPromptBuilder systemPromptBuilder;
    private final ToolRegistry toolRegistry;

    public AgentLoop(KnowledgeBase knowledgeBase,
                     LlmService llmService,
                     SystemPromptBuilder systemPromptBuilder,
                     ToolRegistry toolRegistry) {
        this.knowledgeBase = knowledgeBase;
        this.llmService = llmService;
        this.systemPromptBuilder = systemPromptBuilder;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public AgentResponse respond(AgentRequest request) {
        return respond(request, NO_STREAM);
    }

    @Override
    public AgentResponse respond(AgentRequest request, StreamListener listener) {
        try {
            return generateResponse(request, listener);
        } catch (LlmException e) {
            // A failure is not a generated answer, so do not attach citations or a knowledge disclaimer.
            return new AgentResponse(e.getMessage(), List.of(), false);
        } catch (CancellationException e) {
            return new AgentResponse(new LlmException("llm.interrupted").getMessage(), List.of(), false);
        }
    }

    private AgentResponse generateResponse(AgentRequest request, StreamListener listener) {
        checkInterrupted(listener);
        validate(request);
        if (userPrompt(request, NO_RETRIEVAL).length() > MAX_INPUT_CHARS) {
            throw new LlmException("agent.inputTooLarge");
        }
        Retrieved retrieved = retrieve(request, listener);
        Set<Citation> citations = new LinkedHashSet<>();
        retrieved.citable().forEach(chunk -> citations.add(new Citation(chunk.document(), chunk.pageOrScope())));
        List<LlmService.Message> messages = new ArrayList<>();
        messages.add(LlmService.Message.user(userPrompt(request, retrieved)));
        Set<String> callIds = new HashSet<>();
        List<LlmService.ToolSpec> availableTools = request.action() == AgentAction.GENERATE_COMMIT_MESSAGE
                ? List.of() : toolRegistry.specs();
        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            List<LlmService.ToolSpec> tools = round < MAX_TOOL_ROUNDS ? availableTools : List.of();
            LlmService.Result result = complete(request, messages, tools, round == MAX_TOOL_ROUNDS, listener);
            checkInterrupted(listener);
            if (result == null) {
                throw new LlmException("llm.invalidResponse");
            }
            if (result.toolCalls().isEmpty()) {
                if (blank(result.content())) {
                    throw new LlmException("llm.invalidResponse");
                }
                return new AgentResponse(result.content(), List.copyOf(citations), citations.isEmpty());
            }
            if (tools.isEmpty() || result.toolCalls().size() > MAX_CALLS_PER_ROUND) {
                throw new LlmException("agent.toolLimit");
            }
            // Validate the entire batch before executing any tool from it.
            for (LlmService.ToolCall call : result.toolCalls()) {
                if (blank(call.id()) || blank(call.name()) || call.argumentsJson() == null || !callIds.add(call.id())) {
                    throw new LlmException("llm.invalidResponse");
                }
            }
            messages.add(LlmService.Message.assistant(result));
            checkContextSize(messages);
            // Text streamed before the model decided to call tools is not the final answer; tell the UI to reset.
            listener.onToolCalls(result.toolCalls().stream().map(LlmService.ToolCall::name).toList());
            for (LlmService.ToolCall call : result.toolCalls()) {
                checkInterrupted(listener);
                Tool.ExecutionResult output = toolRegistry.executeWithSources(call);
                messages.add(LlmService.Message.tool(call.id(), output.text()));
                citations.addAll(output.citations());
                checkContextSize(messages);
            }
        }
        throw new LlmException("agent.toolLimit");
    }

    private void validate(AgentRequest request) {
        if (request == null || request.action() == null) {
            throw new LlmException("agent.invalidRequest");
        }
        switch (request.action()) {
            case ASK -> {
                if (blank(request.userInput())) {
                    throw new LlmException("agent.questionRequired");
                }
            }
            case GENERATE_UNIT_TEST -> {
                if (blank(request.userInput())) {
                    throw new LlmException("agent.scenarioRequired");
                }
                if (request.editorContext() == null
                        || (blank(request.editorContext().selectedCode())
                        && blank(request.editorContext().sourceCode()))) {
                    throw new LlmException("agent.codeRequired");
                }
            }
            case GENERATE_COMMIT_MESSAGE -> {
                if (blank(request.stagedDiff())) {
                    throw new LlmException("agent.diffRequired");
                }
            }
            case CREATIVE -> {
                // The creative action writes exercises about the given code, so it needs code to build on.
                if (request.editorContext() == null
                        || (blank(request.editorContext().selectedCode())
                        && blank(request.editorContext().sourceCode()))) {
                    throw new LlmException("agent.practiceCodeRequired");
                }
            }
        }
    }

    private Retrieved retrieve(AgentRequest request, StreamListener listener) {
        if (request.action() == AgentAction.GENERATE_COMMIT_MESSAGE) {
            return NO_RETRIEVAL;
        }
        try {
            checkInterrupted(listener);
            String query = retrievalQuery(request);
            List<KnowledgeChunk> chunks = knowledgeBase.retrieve(query, TOP_K).stream()
                    .limit(TOP_K).toList();
            checkInterrupted(listener);
            if (!chunks.isEmpty()) {
                return new Retrieved(validated(chunks), List.of());
            }
            // The gate rejected the question. Its judgement is precision-first, so the best-ranked chunk can
            // still be the page that answers it; offer that one as unverified context instead of nothing.
            List<KnowledgeChunk> context = knowledgeBase.lowConfidenceContext(query, LOW_CONFIDENCE_TOP_K).stream()
                    .limit(LOW_CONFIDENCE_TOP_K).toList();
            checkInterrupted(listener);
            return new Retrieved(List.of(), validated(context));
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            checkInterrupted(listener);
            throw new LlmException("agent.retrievalFailed");
        }
    }

    private List<KnowledgeChunk> validated(List<KnowledgeChunk> chunks) {
        if (chunks.stream().anyMatch(c -> c == null || blank(c.text())
                || blank(c.document()) || blank(c.pageOrScope()))) {
            throw new LlmException("agent.retrievalFailed");
        }
        return chunks;
    }

    /**
     * Retrieval query: the creative action lets the user skip the extra requirements, and then the selected
     * code (or the whole file) drives retrieval. That keeps an empty string out of retrieval and lands much
     * closer to the concepts in the code than a vague "give me exercises" would.
     */
    private String retrievalQuery(AgentRequest request) {
        if (blank(request.userInput()) && request.editorContext() != null) {
            String selected = request.editorContext().selectedCode();
            return blank(selected) ? request.editorContext().sourceCode() : selected;
        }
        return request.userInput();
    }

    private LlmService.Result complete(AgentRequest request, List<LlmService.Message> messages,
                                       List<LlmService.ToolSpec> tools, boolean finalRound,
                                       StreamListener listener) {
        checkInterrupted(listener);
        checkContextSize(messages);
        String prompt = systemPromptBuilder.build(request.action());
        if (finalRound) {
            prompt += "\nThe tool call budget is exhausted. Produce the final answer from the results you "
                    + "already have, "
                    + "state clearly what is still missing, and do not call any more tools.\n";
        }
        // A question is worth reasoning about; the three smart actions only need the artefact, so their
        // answer does not wait for a deliberation pass the interface never shows.
        return llmService.complete(new LlmService.Request(
                prompt, messages, tools, request.action() == AgentAction.ASK), listener);
    }

    private String userPrompt(AgentRequest request, Retrieved retrieved) {
        StringBuilder prompt = new StringBuilder();
        appendChunks(prompt, "Retrieved course material:\n", retrieved.citable());
        // Marked loudly: the model may use these, but rule 5 of the citation rules forbids citing them.
        appendChunks(prompt, "Course material that did not pass the relevance gate, unverified and possibly "
                + "unrelated; use it only as a hint and do not cite it:\n", retrieved.context());
        if (request.action() != AgentAction.GENERATE_COMMIT_MESSAGE && request.editorContext() != null) {
            if (request.editorContext().filePath() != null) {
                prompt.append("File path: %s\n".formatted(request.editorContext().filePath()));
            }
            if (request.editorContext().sourceCode() != null && !request.editorContext().sourceCode().isBlank()) {
                prompt.append("File source:\n%s\n\n".formatted(request.editorContext().sourceCode()));
            }
            if (request.editorContext().selectedCode() != null && !request.editorContext().selectedCode().isBlank()) {
                prompt.append("Selected code:\n%s\n\n".formatted(request.editorContext().selectedCode()));
            }
        }
        if (request.action() == AgentAction.GENERATE_COMMIT_MESSAGE) {
            prompt.append("Staged changes:\n%s\n\n".formatted(request.stagedDiff()));
        }
        if (request.userInput() != null) {
            prompt.append("User input:\n%s".formatted(request.userInput()));
        }
        return prompt.toString();
    }

    private void appendChunks(StringBuilder prompt, String heading, List<KnowledgeChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        prompt.append(heading);
        for (KnowledgeChunk chunk : chunks) {
            prompt.append("- [source: %s, page/scope: %s]\n%s\n"
                    .formatted(chunk.document(), chunk.pageOrScope(), chunk.text()));
        }
        prompt.append('\n');
    }

    private void checkContextSize(List<LlmService.Message> messages) {
        long size = 0;
        for (LlmService.Message message : messages) {
            size += length(message.content()) + length(message.reasoningContent()) + length(message.toolCallId());
            for (LlmService.ToolCall call : message.toolCalls()) {
                size += length(call.id()) + length(call.name()) + length(call.argumentsJson());
            }
        }
        if (size > MAX_CONTEXT_CHARS) {
            throw new LlmException("agent.contextTooLarge");
        }
    }

    private long length(String value) {
        return value == null ? 0 : value.length();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * What retrieval produced for this question.
     *
     * @param citable chunks that passed the relevance gate; each one becomes a citation
     * @param context chunks that did not, offered to the model as unverified material and never cited
     */
    private record Retrieved(List<KnowledgeChunk> citable, List<KnowledgeChunk> context) {
    }

    private void checkInterrupted(StreamListener listener) {
        if (listener.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
