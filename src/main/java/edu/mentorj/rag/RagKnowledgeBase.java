package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.Statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 课程材料知识库：DocumentProcessor 切出的知识块入库，供 agent 检索。
 *
 * <p>检索分两路，排名用 RRF（reciprocal rank fusion）融合：BM25 词法、embedding 语义。语义那一路
 * 需要索引里有向量且配了 API key，任一条件不满足就自动退回纯 BM25，引用与页码照样有效。
 *
 * <p>{@code KnowledgeChunk.score} 是融合分并归一化到 (0, 1]：在两路都排第一的块得 1.0。之所以不
 * 直接暴露 BM25 原始分，是它没有上界（可能是 0.5 也可能是 18），界面无法展示，也没法和语义分放在
 * 一起比较。
 *
 * <p>带索引文件路径时，索引在**首次使用时**才从磁盘加载，不在构造函数里做：项目级 service 是在
 * EDT 上实例化的，构造函数里读文件等于往 EDT 上塞磁盘 IO。
 */
public final class RagKnowledgeBase implements KnowledgeBase {

    private static final Logger LOG = Logger.getLogger(RagKnowledgeBase.class.getName());

    private static final int RRF_K = 60;
    /** 每路各取前若干名参与融合；名次再靠后的块不值得占用上下文。 */
    private static final int RRF_DEPTH = 20;

    private final Path indexFile;
    private final EmbeddingProvider embeddingProvider;

    private volatile List<KnowledgeChunk> chunks = List.of();
    private volatile List<float[]> vectors = List.of();
    private volatile Bm25Index bm25 = Bm25Index.of(List.of());
    private volatile Instant indexedAt;
    /**
     * 语义检索是否还可信。端点挂掉后不再每次提问都去等超时，本次会话直接退回 BM25；重新导入会打开它。
     */
    private volatile boolean semanticUsable = true;
    /** 只在 {@link #ensureLoaded()} 与写入方法里读写，由 this 的锁保护。 */
    private boolean loaded;

    /** 纯内存知识库，退出即丢，用于测试与没有项目路径的场景。 */
    public RagKnowledgeBase() {
        this(null, null);
    }

    public RagKnowledgeBase(Path indexFile) {
        this(indexFile, null);
    }

    /**
     * @param indexFile         索引文件路径；{@code null} 表示只存内存
     * @param embeddingProvider 向量化实现；{@code null} 表示只用 BM25
     */
    public RagKnowledgeBase(Path indexFile, EmbeddingProvider embeddingProvider) {
        this.indexFile = indexFile;
        this.embeddingProvider = embeddingProvider;
    }

    @Override
    public List<KnowledgeChunk> retrieve(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        ensureLoaded();
        List<KnowledgeChunk> current = chunks;
        if (current.isEmpty()) {
            return List.of();
        }
        List<List<Integer>> rankings = new ArrayList<>();
        rankings.add(bm25.search(query, RRF_DEPTH));
        List<Integer> semantic = semanticRanking(query, current);
        if (!semantic.isEmpty()) {
            rankings.add(semantic);
        }
        return fuse(rankings, topK, current);
    }

    /**
     * 用 SEMANTIC_DEPTH 之外的语义分数做排名。
     *
     * @return 命中的块号，按余弦降序；没有向量、没配 provider 或调用失败时返回空表
     */
    private List<Integer> semanticRanking(String query, List<KnowledgeChunk> current) {
        List<float[]> currentVectors = vectors;
        if (embeddingProvider == null || !semanticUsable || currentVectors.size() != current.size()) {
            return List.of();
        }
        float[] queryVector;
        try {
            queryVector = embeddingProvider.embedQuery(query);
        } catch (IOException e) {
            // 端点不可用不该让提问失败：关掉语义这一路，本次会话老老实实用 BM25。
            semanticUsable = false;
            LOG.log(Level.WARNING, "语义检索不可用，本次会话退回纯 BM25：" + e.getMessage(), e);
            return List.of();
        }
        Map<Integer, Double> scores = new HashMap<>();
        for (int i = 0; i < currentVectors.size(); i++) {
            double similarity = cosine(queryVector, currentVectors.get(i));
            if (similarity > 0) {
                scores.put(i, similarity);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(RRF_DEPTH)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 向量来自不同实现时未必归一化，所以老老实实除以模长，而不是直接用点积。 */
    private static double cosine(float[] left, float[] right) {
        if (left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /** 用新知识块整体替换现有索引，并写盘。写盘失败时降级为本次会话内存有效。 */
    public synchronized void index(List<KnowledgeChunk> newChunks) {
        List<KnowledgeChunk> copy = List.copyOf(newChunks);
        List<float[]> newVectors = embed(copy);
        Bm25Index newIndex = Bm25Index.of(copy);
        Instant now = Instant.now();
        if (indexFile != null) {
            try {
                IndexStore.write(indexFile, copy, newVectors, now);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "索引写盘失败，本次会话仍可用，重启后需重新导入：" + indexFile, e);
            }
        }
        this.chunks = copy;
        this.vectors = newVectors;
        this.bm25 = newIndex;
        this.indexedAt = now;
        this.semanticUsable = true;
        this.loaded = true;
    }

    /**
     * 逐块向量化。
     *
     * <p>失败时返回空表而不是抛异常：导入的文档仍然值得留住，检索只是退回 BM25，好过整批导入白做。
     */
    private List<float[]> embed(List<KnowledgeChunk> chunks) {
        if (embeddingProvider == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> texts = chunks.stream().map(KnowledgeChunk::text).toList();
        try {
            List<float[]> newVectors = embeddingProvider.embed(texts);
            if (newVectors.size() != chunks.size()) {
                LOG.warning("向量数与知识块数不一致（" + newVectors.size() + " vs " + chunks.size() + "），"
                        + "本次只建词法索引");
                return List.of();
            }
            return List.copyOf(newVectors);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "向量化失败，本次只建词法索引：" + e.getMessage(), e);
            return List.of();
        }
    }

    /** 清空索引并删掉磁盘上的索引文件。 */
    public synchronized void clear() {
        this.chunks = List.of();
        this.vectors = List.of();
        this.bm25 = Bm25Index.of(List.of());
        this.indexedAt = null;
        this.semanticUsable = true;
        this.loaded = true;
        if (indexFile != null) {
            try {
                Files.deleteIfExists(indexFile);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "索引文件删除失败：" + indexFile, e);
            }
        }
    }

    public Statistics statistics() {
        ensureLoaded();
        List<KnowledgeChunk> current = chunks;
        TreeSet<String> documents = new TreeSet<>();
        for (KnowledgeChunk chunk : current) {
            documents.add(chunk.document());
        }
        return new Statistics(List.copyOf(documents), current.size(), indexedAt);
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (indexFile == null) {
            return;
        }
        try {
            IndexStore.Loaded restored = IndexStore.read(indexFile);
            this.chunks = restored.chunks();
            this.vectors = restored.vectors();
            this.bm25 = Bm25Index.of(restored.chunks());
            this.indexedAt = restored.generatedAt();
        } catch (IOException e) {
            // 索引损坏时按“没有索引”处理：用户重新导入即可，不能因此让侧边栏打不开。
            LOG.log(Level.WARNING, "索引读取失败，按空索引处理：" + indexFile, e);
        }
    }

    private List<KnowledgeChunk> fuse(List<List<Integer>> rankings, int topK, List<KnowledgeChunk> current) {
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
                .map(entry -> scored(current.get(entry.getKey()), entry.getValue() / bestPossible))
                .toList();
    }

    private static KnowledgeChunk scored(KnowledgeChunk chunk, double score) {
        return new KnowledgeChunk(chunk.text(), chunk.document(), chunk.pageOrScope(), score);
    }
}
