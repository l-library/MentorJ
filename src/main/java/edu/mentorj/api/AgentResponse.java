package edu.mentorj.api;

import java.util.List;

/**
 * agent 返回给界面组的一次回答。
 *
 * @param text                回答正文
 * @param citations           提供给 LLM 的知识块来源，检索到材料时必须非空
 * @param generalKnowledgeOnly {@code true} 表示未检索到课程材料，界面组必须展示通用知识声明
 */
public record AgentResponse(String text, List<Citation> citations, boolean generalKnowledgeOnly) {
}
