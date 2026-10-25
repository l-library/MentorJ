package edu.mentorj.api;

import java.util.List;

/** 课程材料知识库，RAG 组实现，agent 核心组调用。 */
public interface KnowledgeBase {

    /**
     * 检索与查询最相关的知识块。
     *
     * @param query 查询文本
     * @param topK  最多返回的知识块数
     * @return 按 score 降序的知识块列表，无结果返回空表，不返回 {@code null}
     */
    List<KnowledgeChunk> retrieve(String query, int topK);
}
