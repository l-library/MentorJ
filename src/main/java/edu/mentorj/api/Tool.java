package edu.mentorj.api;

/** agent 循环可调用的工具，各组提供实现后注册进 ToolRegistry。 */
public interface Tool {

    /** 工具名、描述与参数 JSON Schema，交给 LLM 决定何时调用。 */
    LlmService.ToolSpec spec();

    /**
     * 执行一次工具调用。
     *
     * @param argumentsJson 参数 JSON 文本
     * @return 回填给 LLM 的文本结果
     */
    String execute(String argumentsJson);
}
