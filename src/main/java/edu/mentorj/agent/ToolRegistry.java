package edu.mentorj.agent;

import edu.mentorj.api.LlmService;
import edu.mentorj.api.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工具注册表：各组注册 Tool 实现，agent 循环按 LLM 的工具调用执行它们。 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public synchronized void register(Tool tool) {
        tools.put(tool.spec().name(), tool);
    }

    public synchronized List<LlmService.ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /** 执行一次工具调用，工具报错时返回错误文本而不是中断循环。 */
    public synchronized String execute(LlmService.ToolCall call) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return "unknown tool: " + call.name();
        }
        try {
            return tool.execute(call.argumentsJson());
        } catch (RuntimeException e) {
            return "tool failed: " + e.getMessage();
        }
    }
}
