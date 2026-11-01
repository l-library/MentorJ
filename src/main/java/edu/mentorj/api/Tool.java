package edu.mentorj.api;

import java.util.List;

/** A tool the agent loop can call; each team registers its implementations in ToolRegistry. */
public interface Tool {

    /** Tool name, description, and parameter JSON Schema, handed to the LLM so it can decide when to call the tool. */
    LlmService.ToolSpec spec();

    /**
     * Run one tool call.
     *
     * @param argumentsJson arguments as JSON text
     * @return the text result fed back to the LLM
     */
    String execute(String argumentsJson);

    /** Retrieval tools may override this to supply real sources; older tools return text only by default. */
    default ExecutionResult executeWithSources(String argumentsJson) {
        return new ExecutionResult(execute(argumentsJson), List.of());
    }

    record ExecutionResult(String text, List<Citation> citations) {
        public ExecutionResult {
            citations = List.copyOf(citations);
        }
    }
}
