package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.api.KnowledgeChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程材料知识库：DocumentProcessor 切出的知识块入库，供 agent 检索。
 *
 * <p>检索分两路，排名用 RRF（reciprocal rank fusion）融合：BM25 词法、embedding 语义。当前只有
 * BM25 一路，语义那一路接入后加进 {@link #rankings} 即可，融合与打分口径不变。
 *
 * <p>{@code KnowledgeChunk.score} 是融合分并归一化到 (0, 1]：在每一路都排第一的块得 1.0。
 * 之所以不直接暴露 BM25 原始分，是它没有上界（可能是 0.5 也可能是 18），界面无法展示，也没法和
 * 语义分放在一起比较。
 */
public final class RagKnowledgeBase implements KnowledgeBase {

    private static final int RRF_K = 60;
    /** 每路各取前若干名参与融合；名次再靠后的块不值得占用上下文。 */
    private static final int RRF_DEPTH = 20;

    private volatile List<KnowledgeChunk> chunks = List.of();
    private volatile Bm25Index bm25 = Bm25Index.of(List.of());

    public void index(List<KnowledgeChunk> newChunks) {
        List<KnowledgeChunk> copy = List.copyOf(newChunks);
        this.chunks = copy;
        this.bm25 = Bm25Index.of(copy);
    }

    @Override
    public List<KnowledgeChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0 || chunks.isEmpty()) {
            return List.of();
        }
        List<List<Integer>> rankings = new ArrayList<>();
        rankings.add(bm25.search(query, RRF_DEPTH));
        return fuse(rankings, topK);
    }

    private List<KnowledgeChunk> fuse(List<List<Integer>> rankings, int topK) {
        Map<Integer, Double> fused = new HashMap<>();
        for (List<Integer> ranking : rankings) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                fused.merge(ranking.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        double bestPossible = rankings.size() / (double) (RRF_K + 1);
        return fused.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(topK)
                .map(entry -> scored(chunks.get(entry.getKey()), entry.getValue() / bestPossible))
                .toList();
    }

    private static KnowledgeChunk scored(KnowledgeChunk chunk, double score) {
        return new KnowledgeChunk(chunk.text(), chunk.document(), chunk.pageOrScope(), score);
    }
}
