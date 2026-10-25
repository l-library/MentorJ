package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.api.KnowledgeChunk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 课程材料知识库：DocumentProcessor 切出的知识块入库，供 agent 检索。 */
public final class RagKnowledgeBase implements KnowledgeBase {

    private final List<KnowledgeChunk> chunks = new CopyOnWriteArrayList<>();

    public void index(List<KnowledgeChunk> newChunks) {
        chunks.addAll(newChunks);
    }

    @Override
    public List<KnowledgeChunk> retrieve(String query, int topK) {
        // TODO(RAG 组): 语义检索（embedding / 向量相似度）并按相关性取 topK，当前占位返回空
        return List.of();
    }
}
