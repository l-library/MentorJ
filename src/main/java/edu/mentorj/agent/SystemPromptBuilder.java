package edu.mentorj.agent;

import edu.mentorj.api.AgentAction;

/**
 * 系统提示词：助教身份 + 引用规则 + 分操作指令。内容归 agent 核心组维护。
 */
public final class SystemPromptBuilder {

    /** 无检索结果时要求 LLM 明说的英文示例，与 API.md 的免责声明约定一致。 */
    public static final String GENERAL_KNOWLEDGE_NOTICE =
            "Response is based on general knowledge; no specific course material is referenced";

    public String build(AgentAction action) {
        return persona() + citationRules() + taskInstruction(action);
    }

    private String persona() {
        return """
                你是《Java 企业级应用开发》课程的助教（TA），面向大学 Java 学习者，
                用中文回答课程答疑与编程问题，结合通用 Java 知识与课程材料。

                """;
    }

    private String citationRules() {
        return """
                回答规则：
                1. 引用课程材料时，必须标注来源文档与页码/范围。
                2. 上下文中没有相关课程材料时，明确说明回答仅基于通用知识（例如：“%s”）。

                """.formatted(GENERAL_KNOWLEDGE_NOTICE);
    }

    private String taskInstruction(AgentAction action) {
        // TODO(agent 核心组): 细化各智能操作的生成指令
        return switch (action) {
            case ASK -> "本次任务：回答用户问题。\n";
            case GENERATE_UNIT_TEST ->
                    "本次任务：针对用户描述的场景，结合给定源代码生成 JUnit 测试类。\n";
            case GENERATE_COMMIT_MESSAGE ->
                    "本次任务：分析给定 staged 改动的语义意图，按 Conventional Commits 规范生成提交消息。\n";
            case CREATIVE -> "本次任务：创意智能操作，方案待定。\n";
        };
    }
}
