package edu.mentorj.agent;

import com.google.gson.JsonParser;
import edu.mentorj.api.LlmService;
import edu.mentorj.api.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** 工具注册表：各组注册 Tool 实现，agent 循环按 LLM 的工具调用执行它们。 */
public final class ToolRegistry {

    private static final int MAX_OUTPUT_CHARS = 20_000;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public synchronized void register(Tool tool) {
        String name = tool.spec().name();
        if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}") || tools.containsKey(name)) {
            throw new IllegalArgumentException("Invalid or duplicate tool name");
        }
        tools.put(name, tool);
    }

    public synchronized List<LlmService.ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /** 执行一次工具调用，工具报错时返回错误文本而不是中断循环。 */
    public String execute(LlmService.ToolCall call) {
        return executeWithSources(call).text();
    }

    public Tool.ExecutionResult executeWithSources(LlmService.ToolCall call) {
        checkInterrupted();
        Tool tool;
        synchronized (this) {
            tool = tools.get(call.name());
        }
        if (tool == null) {
            return failure("unknown tool; choose a registered tool");
        }
        try {
            if (call.argumentsJson() == null || call.argumentsJson().length() > MAX_OUTPUT_CHARS
                    || !JsonParser.parseString(call.argumentsJson()).isJsonObject()) {
                return failure("arguments must be a JSON object within 20000 characters");
            }
        } catch (RuntimeException e) {
            return failure("arguments must be a valid JSON object");
        }
        try {
            // Do not hold the registry lock across potentially slow I/O.
            Tool.ExecutionResult result = tool.executeWithSources(call.argumentsJson());
            checkInterrupted();
            if (result == null || result.text() == null || result.text().isBlank()) {
                return failure("tool returned no output");
            }
            if (result.text().length() > MAX_OUTPUT_CHARS) {
                return failure("tool output is too large; narrow the query");
            }
            if (result.citations().size() > 100 || result.citations().stream().anyMatch(c ->
                    c.document() == null || c.document().isBlank() || c.pageOrScope() == null
                            || c.pageOrScope().isBlank())) {
                return failure("tool returned invalid source metadata");
            }
            return result;
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            checkInterrupted();
            // Upstream exception messages may contain credentials or internal paths.
            return failure("tool execution failed; do not treat this as an empty search result");
        }
    }

    private Tool.ExecutionResult failure(String reason) {
        return new Tool.ExecutionResult("tool_error: " + reason, List.of());
    }

    private void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }
}
