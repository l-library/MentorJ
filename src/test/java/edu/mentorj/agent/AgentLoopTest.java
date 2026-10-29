package edu.mentorj.agent;

import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.AgentResponse;
import edu.mentorj.api.Citation;
import edu.mentorj.api.EditorContext;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.LlmService;
import edu.mentorj.api.Tool;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AgentLoopTest {

    @Test
    public void suppliesCourseMaterialAndFullSourceToModel() {
        AtomicReference<LlmService.Request> sent = new AtomicReference<>();
        AgentLoop agent = new AgentLoop(
                (query, topK) -> List.of(new KnowledgeChunk("Use assertions", "JUnit.pdf", "3-4", 0.9)),
                request -> {
                    sent.set(request);
                    return new LlmService.Result("generated test", List.of());
                }, new SystemPromptBuilder(), new ToolRegistry());
        AgentResponse response = agent.respond(new AgentRequest(AgentAction.GENERATE_UNIT_TEST, "test empty input",
                new EditorContext("src/Example.java", "class Example {}", null), null));
        String prompt = sent.get().messages().get(0).content();
        assertTrue(prompt.contains("Use assertions"));
        assertTrue(prompt.contains("JUnit.pdf"));
        assertTrue(prompt.contains("3-4"));
        assertTrue(prompt.contains("src/Example.java"));
        assertTrue(prompt.contains("class Example {}"));
        assertTrue(prompt.contains("test empty input"));
        assertEquals(List.of(new Citation("JUnit.pdf", "3-4")), response.citations());
        assertFalse(response.generalKnowledgeOnly());
    }

    @Test
    public void forwardsStagedDiffAndMarksNoCourseMaterial() {
        AtomicReference<LlmService.Request> sent = new AtomicReference<>();
        AgentLoop agent = new AgentLoop((query, topK) -> List.of(), request -> {
            sent.set(request);
            return new LlmService.Result("fix: handle empty input", List.of());
        }, new SystemPromptBuilder(), new ToolRegistry());
        AgentResponse response = agent.respond(new AgentRequest(AgentAction.GENERATE_COMMIT_MESSAGE, null, null,
                "diff --git a/Example.java b/Example.java\n+if (input.isEmpty()) return;"));
        assertTrue(sent.get().messages().get(0).content().contains("+if (input.isEmpty()) return;"));
        assertTrue(sent.get().systemPrompt().contains("Conventional Commits"));
        assertTrue(response.generalKnowledgeOnly());
        assertTrue(response.citations().isEmpty());
    }

    @Test
    public void returnsActionableConfigurationErrorToExistingUi() {
        AgentLoop agent = new AgentLoop((query, topK) -> List.of(), request -> {
            throw new LlmException("llm.missingSetting", "MENTORJ_LLM_API_KEY");
        }, new SystemPromptBuilder(), new ToolRegistry());
        AgentResponse response = agent.respond(new AgentRequest(AgentAction.ASK, "What is Java?", null, null));
        assertTrue(response.text().contains("MENTORJ_LLM_API_KEY"));
        assertTrue(response.citations().isEmpty());
        assertFalse(response.generalKnowledgeOnly());
    }

    private AgentRequest question() {
        return new AgentRequest(AgentAction.ASK, "Explain dependency injection", null, null);
    }

    private ToolRegistry registry(AtomicInteger executions, List<Citation> sources) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public LlmService.ToolSpec spec() {
                return new LlmService.ToolSpec("search", "Search materials", "{\"type\":\"object\"}");
            }

            @Override
            public String execute(String arguments) {
                executions.incrementAndGet();
                return "Use constructor injection [Spring.pdf, 5]";
            }

            @Override
            public ExecutionResult executeWithSources(String arguments) {
                return new ExecutionResult(execute(arguments), sources);
            }
        });
        return registry;
    }

    private LlmService.Result search(String id) {
        return new LlmService.Result(null, List.of(new LlmService.ToolCall(id, "search", "{}")), "continuation");
    }

    @Test
    public void correlatesMultipleToolsAndDeduplicatesAllSources() {
        List<LlmService.Request> sent = new ArrayList<>();
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = registry(executions,
                List.of(new Citation("Intro.pdf", "2"), new Citation("Spring.pdf", "5")));
        AgentLoop agent = new AgentLoop(
                (query, topK) -> List.of(new KnowledgeChunk("DI principle", "Intro.pdf", "2", 1)), request -> {
                    sent.add(request);
                    if (sent.size() == 1) {
                        return new LlmService.Result(null, List.of(
                                new LlmService.ToolCall("a", "search", "{}"),
                                new LlmService.ToolCall("b", "search", "{}")), "continuation");
                    }
                    return new LlmService.Result("Constructor injection", List.of());
                }, new SystemPromptBuilder(), registry);
        AgentResponse response = agent.respond(question());
        assertEquals(2, executions.get());
        assertEquals(1, sent.get(0).messages().size()); // Request snapshots must not grow with later rounds.
        List<LlmService.Message> history = sent.get(1).messages();
        assertEquals(2, history.get(1).toolCalls().size());
        assertEquals("continuation", history.get(1).reasoningContent());
        assertEquals("a", history.get(2).toolCallId());
        assertEquals("b", history.get(3).toolCallId());
        assertEquals(List.of(new Citation("Intro.pdf", "2"), new Citation("Spring.pdf", "5")), response.citations());
        assertFalse(response.generalKnowledgeOnly());
    }

    @Test
    public void includesSourcesFoundOnlyByToolsAndDoesNotLeakThemToNextRequest() {
        AtomicInteger calls = new AtomicInteger();
        AgentLoop agent = new AgentLoop((query, topK) -> List.of(), request ->
                calls.getAndIncrement() == 0 ? search("a") : new LlmService.Result("answer", List.of()),
                new SystemPromptBuilder(), registry(new AtomicInteger(), List.of(new Citation("Spring.pdf", "5"))));
        AgentResponse first = agent.respond(question());
        assertEquals(List.of(new Citation("Spring.pdf", "5")), first.citations());
        assertFalse(first.generalKnowledgeOnly());
        AgentResponse second = agent.respond(question());
        assertTrue(second.citations().isEmpty());
        assertTrue(second.generalKnowledgeOnly());
    }

    @Test
    public void disablesToolsAfterThreeRoundsAndAllowsFinalAnswer() {
        List<LlmService.Request> sent = new ArrayList<>();
        AtomicInteger executions = new AtomicInteger();
        AgentLoop agent = new AgentLoop((query, topK) -> List.of(), request -> {
            sent.add(request);
            return sent.size() < 4 ? search("call_" + sent.size()) : new LlmService.Result("final", List.of());
        }, new SystemPromptBuilder(), registry(executions, List.of()));
        assertEquals("final", agent.respond(question()).text());
        assertEquals(3, executions.get());
        assertEquals(4, sent.size());
        assertTrue(sent.get(3).tools().isEmpty());
        assertTrue(sent.get(3).systemPrompt().contains("勿再调用工具"));
    }

    @Test
    public void stopsModelThatKeepsRequestingTools() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AgentLoop agent = new AgentLoop((query, topK) -> List.of(),
                request -> search("call_" + calls.incrementAndGet()),
                new SystemPromptBuilder(), registry(executions, List.of()));
        AgentResponse response = agent.respond(question());
        assertTrue(response.text().contains("工具调用限制"));
        assertEquals(4, calls.get());
        assertEquals(3, executions.get());
        assertFalse(response.generalKnowledgeOnly());
    }

    @Test
    public void rejectsDuplicateIdsAndOversizedBatchesBeforeExecution() {
        for (int count : new int[]{2, 9}) {
            AtomicInteger executions = new AtomicInteger();
            List<LlmService.ToolCall> calls = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                calls.add(new LlmService.ToolCall(count == 2 ? "duplicate" : "call_" + i, "search", "{}"));
            }
            AgentLoop agent = new AgentLoop((query, topK) -> List.of(), request -> new LlmService.Result(null, calls),
                    new SystemPromptBuilder(), registry(executions, List.of()));
            AgentResponse response = agent.respond(question());
            assertFalse(response.text().isBlank());
            assertFalse(response.generalKnowledgeOnly());
            assertEquals(0, executions.get());
        }
    }

    @Test
    public void validatesRequiredInputsBeforeRetrievalOrGeneration() {
        AgentLoop agent = new AgentLoop((query, topK) -> {
            throw new AssertionError("Invalid input must not reach retrieval");
        }, request -> {
            throw new AssertionError("Invalid input must not reach the model");
        }, new SystemPromptBuilder(), new ToolRegistry());
        for (AgentRequest request : new AgentRequest[]{null, new AgentRequest(null, "question", null, null),
                new AgentRequest(AgentAction.ASK, " ", null, null),
                new AgentRequest(AgentAction.GENERATE_UNIT_TEST, null,
                        new EditorContext(null, "class A {}", null), null),
                new AgentRequest(AgentAction.GENERATE_UNIT_TEST, "test errors", null, null),
                new AgentRequest(AgentAction.GENERATE_COMMIT_MESSAGE, "notes", null, " "),
                new AgentRequest(AgentAction.CREATIVE, "anything", null, null),
                new AgentRequest(AgentAction.ASK, "x".repeat(60_001), null, null)}) {
            AgentResponse response = agent.respond(request);
            assertFalse(response.text().isBlank());
            assertFalse(response.generalKnowledgeOnly());
            assertTrue(response.citations().isEmpty());
        }
    }

    @Test
    public void commitUsesOnlyDiffAndInstructionsWithoutCourseSearchOrTools() {
        AgentLoop agent = new AgentLoop((query, topK) -> {
            throw new AssertionError("Commit must not query course material");
        }, request -> {
            assertTrue(request.tools().isEmpty());
            assertTrue(request.messages().get(0).content().contains("+return value;"));
            assertTrue(request.messages().get(0).content().contains("focus on behavior"));
            assertFalse(request.messages().get(0).content().contains("unrelated-source"));
            assertFalse(request.systemPrompt().contains(SystemPromptBuilder.GENERAL_KNOWLEDGE_NOTICE));
            return new LlmService.Result("fix: return value", List.of());
        }, new SystemPromptBuilder(), registry(new AtomicInteger(), List.of()));
        AgentResponse response = agent.respond(new AgentRequest(AgentAction.GENERATE_COMMIT_MESSAGE,
                "focus on behavior", new EditorContext(null, "unrelated-source", null), "+return value;"));
        assertEquals("fix: return value", response.text());
    }

    @Test
    public void reportsRetrievalFailureWithoutFabricatingGeneralAnswer() {
        AgentLoop agent = new AgentLoop((query, topK) -> {
            throw new IllegalStateException("secret upstream details");
        }, request -> {
            throw new AssertionError("Must not generate after failed initial retrieval");
        }, new SystemPromptBuilder(), new ToolRegistry());
        AgentResponse response = agent.respond(question());
        assertTrue(response.text().contains("检索失败"));
        assertFalse(response.text().contains("secret"));
        assertFalse(response.generalKnowledgeOnly());
    }

    @Test
    public void preservesCancellationBeforeRequestAndDuringRetrieval() {
        AgentLoop agent = new AgentLoop((query, topK) -> {
            throw new CancellationException();
        }, request -> {
            throw new AssertionError("Cancelled request reached model");
        }, new SystemPromptBuilder(), new ToolRegistry());
        assertTrue(agent.respond(question()).text().contains("取消"));
        try {
            Thread.currentThread().interrupt();
            assertTrue(agent.respond(question()).text().contains("取消"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void rejectsEmptyFinalAnswerAndOversizedContext() {
        AgentLoop emptyAnswer = new AgentLoop((query, topK) -> List.of(),
                request -> new LlmService.Result(" ", List.of()), new SystemPromptBuilder(), new ToolRegistry());
        assertFalse(emptyAnswer.respond(question()).generalKnowledgeOnly());
        AgentLoop oversizedContext = new AgentLoop((query, topK) -> List.of(
                new KnowledgeChunk("x".repeat(120_001), "Course.pdf", "1", 1)), request -> {
                    throw new AssertionError("Oversized context reached model");
                }, new SystemPromptBuilder(), new ToolRegistry());
        assertTrue(oversizedContext.respond(question()).text().contains("120000"));
    }
}
