package edu.mentorj.agent;

import edu.mentorj.api.Citation;
import edu.mentorj.api.LlmService;
import edu.mentorj.api.Tool;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ToolRegistryTest {

    private Tool tool(Function<String, String> execute) {
        return new Tool() {
            @Override
            public LlmService.ToolSpec spec() {
                return new LlmService.ToolSpec("search", "Search", "{\"type\":\"object\"}");
            }

            @Override
            public String execute(String arguments) {
                return execute.apply(arguments);
            }
        };
    }

    private LlmService.ToolCall call(String arguments) {
        return new LlmService.ToolCall("id", "search", arguments);
    }

    @Test
    public void preservesLegacyToolsAndRejectsDuplicateRegistration() {
        ToolRegistry registry = new ToolRegistry();
        Tool tool = tool(arguments -> "found result");
        registry.register(tool);
        assertThrows(IllegalArgumentException.class, () -> registry.register(tool));
        assertEquals(1, registry.specs().size());
        Tool.ExecutionResult output = registry.executeWithSources(call("{}"));
        assertEquals("found result", output.text());
        assertTrue(output.citations().isEmpty());
    }

    @Test
    public void rejectsUnknownToolsAndNonObjectArgumentsWithoutExecution() {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(arguments -> {
            executions.incrementAndGet();
            return "result";
        }));
        for (String arguments : new String[]{null, "null", "[]", "42", "{", "\"text\"", "x".repeat(20_001)}) {
            assertTrue(registry.execute(call(arguments)).startsWith("tool_error:"));
        }
        assertTrue(registry.execute(new LlmService.ToolCall("id", "unregistered", "{}"))
                .contains("unknown tool"));
        assertEquals(0, executions.get());
    }

    @Test
    public void sanitizesExecutionErrorsAndDoesNotInventSources() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(arguments -> {
            throw new IllegalStateException("secret-key and private-path");
        }));
        Tool.ExecutionResult output = registry.executeWithSources(call("{}"));
        assertTrue(output.text().startsWith("tool_error:"));
        assertFalse(output.text().contains("secret-key"));
        assertFalse(output.text().contains("private-path"));
        assertTrue(output.citations().isEmpty());
    }

    @Test
    public void rejectsEmptyOrOversizedOutputs() {
        for (String text : new String[]{null, " ", "x".repeat(20_001)}) {
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool(arguments -> text));
            Tool.ExecutionResult output = registry.executeWithSources(call("{}"));
            assertTrue(output.text().startsWith("tool_error:"));
            assertTrue(output.citations().isEmpty());
        }
    }

    @Test
    public void rejectsIncompleteSourceMetadata() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public LlmService.ToolSpec spec() {
                return new LlmService.ToolSpec("search", "Search", "{\"type\":\"object\"}");
            }

            @Override
            public String execute(String arguments) {
                return "content";
            }

            @Override
            public ExecutionResult executeWithSources(String arguments) {
                return new ExecutionResult("content", List.of(new Citation("Course.pdf", " ")));
            }
        });
        Tool.ExecutionResult output = registry.executeWithSources(call("{}"));
        assertTrue(output.text().startsWith("tool_error:"));
        assertTrue(output.citations().isEmpty());
    }

    @Test
    public void propagatesCancellationInsteadOfReturningToolFailure() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool(arguments -> {
            throw new CancellationException();
        }));
        assertThrows(CancellationException.class, () -> registry.execute(call("{}")));
    }
}
