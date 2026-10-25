package edu.mentorj.api;

/** 一次请求要执行的智能操作类型。 */
public enum AgentAction {
    /** 课程问答：侧边栏提问或就选中代码提问。 */
    ASK,
    /** 按自然语言需求为选中的类 / 方法生成 JUnit 测试。 */
    GENERATE_UNIT_TEST,
    /** 分析 staged 改动，生成 Git 提交消息。 */
    GENERATE_COMMIT_MESSAGE,
    /** 团队自拟的创意智能操作，方案待定。 */
    CREATIVE
}
