package edu.mentorj.api;

import java.util.List;

/**
 * LLM call interface, implemented by the agent core team and decoupled from any specific provider and from the UI.
 */
public interface LlmService {

    /** Complete one conversation turn; tool call results are filled in by the caller before calling again. */
    Result complete(Request request);

    /**
     * Complete one conversation turn and stream content increments when the server supports it.
     *
     * <p>{@code listener} is called back on the calling thread; the default implementation degrades to a single
     * return, so existing implementations and tests need no change.
     */
    default Result complete(Request request, StreamListener listener) {
        Result result = complete(request);
        if (!listener.isCancelled() && result.toolCalls().isEmpty() && result.content() != null) {
            listener.onText(result.content());
        }
        return result;
    }

    /**
     * One conversation message.
     *
     * @param role    role: system / user / assistant / tool
     * @param content message body
     */
    record Message(String role, String content, List<ToolCall> toolCalls,
                   String toolCallId, String reasoningContent) {

        public Message {
            toolCalls = List.copyOf(toolCalls);
        }

        public Message(String role, String content) {
            this(role, content, List.of(), null, null);
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        public static Message assistant(Result result) {
            return new Message("assistant", result.content(), result.toolCalls(), null, result.reasoningContent());
        }

        public static Message tool(String callId, String content) {
            return new Message("tool", content, List.of(), callId, null);
        }

        /** Kept for source compatibility only; a real tool result must use the overload that takes a callId. */
        public static Message tool(String content) {
            return new Message("tool", content);
        }
    }

    /**
     * A tool description given to the LLM.
     *
     * @param name                 tool name
     * @param description          what the tool is for
     * @param parametersJsonSchema JSON Schema of the parameters
     */
    record ToolSpec(String name, String description, String parametersJsonSchema) {
    }

    /**
     * One tool call the LLM requested.
     *
     * @param id            call id
     * @param name          tool name
     * @param argumentsJson arguments as JSON text
     */
    record ToolCall(String id, String name, String argumentsJson) {
    }

    /**
     * Input of one LLM call.
     *
     * @param systemPrompt system prompt
     * @param messages     conversation messages in chronological order
     * @param tools        available tools; may be an empty list
     * @param thinking     whether the model may reason before answering; false asks the service to turn that
     *                     pass off, so the answer does not wait for deliberation the caller never shows
     */
    record Request(String systemPrompt, List<Message> messages, List<ToolSpec> tools, boolean thinking) {
        public Request {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }

        public Request(String systemPrompt, List<Message> messages, List<ToolSpec> tools) {
            this(systemPrompt, messages, tools, true);
        }
    }

    /**
     * Result of one LLM call.
     *
     * @param content   answer body; may be empty
     * @param toolCalls tool calls to execute; an empty list when the model requested none
     */
    record Result(String content, List<ToolCall> toolCalls, String reasoningContent) {
        public Result {
            toolCalls = List.copyOf(toolCalls);
        }

        public Result(String content, List<ToolCall> toolCalls) {
            this(content, toolCalls, null);
        }
    }
}
