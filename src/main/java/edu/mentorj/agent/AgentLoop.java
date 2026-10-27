package edu.mentorj.agent;

import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.AgentResponse;
import edu.mentorj.api.AgentService;
import edu.mentorj.api.Citation;
import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.LlmService;
import edu.mentorj.api.Tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * AgentService 实现：检索知识块 → 组装提示词 → 调 LLM → 工具循环 → 组装回答。
 * 知识块来源直接作为回答引用（citations），检索为空时标记 generalKnowledgeOnly。
 */
public final class AgentLoop implements AgentService {

    private static final int TOP_K = 5;
    private static final int MAX_TOOL_ROUNDS = 3;
    private static final int MAX_CALLS_PER_ROUND = 8;
    private static final int MAX_INPUT_CHARS = 60_000;
    private static final int MAX_CONTEXT_CHARS = 120_000;

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
        try {
            return generateResponse(request);
        } catch (LlmException e) {
            // A failure is not a generated answer, so do not attach citations or a knowledge disclaimer.
            return new AgentResponse(e.getMessage(), List.of(), false);
        } catch (CancellationException e) {
            return new AgentResponse(new LlmException("llm.interrupted").getMessage(), List.of(), false);
        }
    }

    private AgentResponse generateResponse(AgentRequest request) {
        checkInterrupted();
        validate(request);
        if (userPrompt(request, List.of()).length() > MAX_INPUT_CHARS) {
            throw new LlmException("agent.inputTooLarge");
        }
        List<KnowledgeChunk> chunks = retrieve(request);
        Set<Citation> citations = new LinkedHashSet<>();
        chunks.forEach(chunk -> citations.add(new Citation(chunk.document(), chunk.pageOrScope())));
        List<LlmService.Message> messages = new ArrayList<>();
        messages.add(LlmService.Message.user(userPrompt(request, chunks)));
        Set<String> callIds = new HashSet<>();
        List<LlmService.ToolSpec> availableTools = request.action() == AgentAction.GENERATE_COMMIT_MESSAGE
                ? List.of() : toolRegistry.specs();
        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            List<LlmService.ToolSpec> tools = round < MAX_TOOL_ROUNDS ? availableTools : List.of();
            LlmService.Result result = complete(request, messages, tools, round == MAX_TOOL_ROUNDS);
            checkInterrupted();
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
            for (LlmService.ToolCall call : result.toolCalls()) {
                checkInterrupted();
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
            case CREATIVE -> throw new LlmException("agent.creativeUnavailable");
        }
    }

    private List<KnowledgeChunk> retrieve(AgentRequest request) {
        if (request.action() == AgentAction.GENERATE_COMMIT_MESSAGE) {
            return List.of();
        }
        try {
            checkInterrupted();
            List<KnowledgeChunk> chunks = knowledgeBase.retrieve(request.userInput(), TOP_K).stream()
                    .limit(TOP_K).toList();
            checkInterrupted();
            if (chunks.stream().anyMatch(c -> c == null || blank(c.text())
                    || blank(c.document()) || blank(c.pageOrScope()))) {
                throw new LlmException("agent.retrievalFailed");
            }
            return chunks;
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            checkInterrupted();
            throw new LlmException("agent.retrievalFailed");
        }
    }

    private LlmService.Result complete(AgentRequest request, List<LlmService.Message> messages,
                                       List<LlmService.ToolSpec> tools, boolean finalRound) {
        checkInterrupted();
        checkContextSize(messages);
        String prompt = systemPromptBuilder.build(request.action());
        if (finalRound) {
            prompt += "\n工具调用次数已用完。请根据已有结果生成最终回答，明确仍然缺失的信息，勿再调用工具。\n";
        }
        return llmService.complete(new LlmService.Request(
                prompt, messages, tools));
    }

    private String userPrompt(AgentRequest request, List<KnowledgeChunk> chunks) {
        StringBuilder prompt = new StringBuilder();
        if (!chunks.isEmpty()) {
            prompt.append("以下是检索到的课程材料：\n");
            for (KnowledgeChunk chunk : chunks) {
                prompt.append("- [来源: %s，页码/范围: %s]\n%s\n"
                        .formatted(chunk.document(), chunk.pageOrScope(), chunk.text()));
            }
            prompt.append('\n');
        }
        if (request.action() != AgentAction.GENERATE_COMMIT_MESSAGE && request.editorContext() != null) {
            if (request.editorContext().filePath() != null) {
                prompt.append("文件路径：%s\n".formatted(request.editorContext().filePath()));
            }
            if (request.editorContext().sourceCode() != null && !request.editorContext().sourceCode().isBlank()) {
                prompt.append("文件源码：\n%s\n\n".formatted(request.editorContext().sourceCode()));
            }
            if (request.editorContext().selectedCode() != null && !request.editorContext().selectedCode().isBlank()) {
                prompt.append("选中的代码：\n%s\n\n".formatted(request.editorContext().selectedCode()));
            }
        }
        if (request.action() == AgentAction.GENERATE_COMMIT_MESSAGE) {
            prompt.append("staged 改动：\n%s\n\n".formatted(request.stagedDiff()));
        }
        if (request.userInput() != null) {
            prompt.append("用户输入：\n%s".formatted(request.userInput()));
        }
        return prompt.toString();
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

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
