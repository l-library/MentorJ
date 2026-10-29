package edu.mentorj.api;

import java.util.List;

/**
 * LLM 调用接口，agent 核心组实现；与具体服务商及 UI 解耦。
 */
public interface LlmService {

    /** 完成一轮对话；工具调用结果由调用方回填后再次调用。 */
    Result complete(Request request);

    /**
     * 一条对话消息。
     *
     * @param role    角色：system / user / assistant / tool
     * @param content 消息正文
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

        /** 仅保留源码兼容；真实工具回填必须使用带 callId 的重载。 */
        public static Message tool(String content) {
            return new Message("tool", content);
        }
    }

    /**
     * 提供给 LLM 的工具描述。
     *
     * @param name                工具名
     * @param description         工具用途描述
     * @param parametersJsonSchema 参数的 JSON Schema
     */
    record ToolSpec(String name, String description, String parametersJsonSchema) {
    }

    /**
     * LLM 发起的一次工具调用。
     *
     * @param id            调用 id
     * @param name          工具名
     * @param argumentsJson 参数 JSON 文本
     */
    record ToolCall(String id, String name, String argumentsJson) {
    }

    /**
     * 一次 LLM 调用的入参。
     *
     * @param systemPrompt 系统提示词
     * @param messages     对话消息，按时间顺序
     * @param tools        可用工具，可为空表
     */
    record Request(String systemPrompt, List<Message> messages, List<ToolSpec> tools) {
        public Request {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
        }
    }

    /**
     * 一次 LLM 调用的结果。
     *
     * @param content   回答正文，可为空
     * @param toolCalls 要执行的工具调用，无工具调用时为空表
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
