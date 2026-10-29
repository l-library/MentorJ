package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.Statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
    /*
     * 相关性闸门。没有闸门时，只要有一个词命中就返回结果，于是“课件里没有的内容”也会带上一
     * 条假引用，而作业要求检索不到时明确声明只用通用知识。
     *
     * 阈值是量出来的（218 块，18 条查询，见 docs/RAG-DESIGN.md §5.2）。为什么不卡绝对余弦：
     * 同一问题的不同写法彼此余弦只有 0.91–0.98，实测“How do I read a file line by line?”是
     * 0.545、全小写版是 0.423，绝对阈值会被措辞直接推过线。改用**相对信号**：
     * 榜首与中位数的差，正例最低 0.319、负例最高 0.280，没有重叠。
     */
    private static final double MIN_TOP_GAP = 0.30;
    /** 榜首余弦足够高时可以直接判定语料覆盖了这个问题，不再要求差值。 */
    private static final double DECISIVE_SIMILARITY = 0.60;
    /** 单个块要进入引用列表的最低余弦，用来剪掉榜首之后的跑题块。 */
    private static final double MIN_SIMILARITY = 0.45;
    /** 没有向量时的退路：查询词至少一半出现在同一块里。 */
    private static final double MIN_COVERAGE = 0.50;

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
        float[] queryVector = queryVector(query);
        boolean semanticReady = queryVector != null && vectors.size() == current.size();
        double[] similarities = semanticReady ? similarities(queryVector) : null;
        if (similarities != null && !coversQuestion(similarities)) {
            return List.of();
        }

        List<Integer> lexical = bm25.search(query, RRF_DEPTH).stream()
                .filter(hit -> similarities != null
                        ? similarities[hit.chunk()] >= MIN_SIMILARITY
                        : hit.coverage() >= MIN_COVERAGE)
                .map(Bm25Index.Hit::chunk)
                .toList();
        List<Integer> semantic = similarities != null
                ? semanticRanking(similarities)
                : List.of();

        List<List<Integer>> rankings = new ArrayList<>();
        if (!lexical.isEmpty()) {
            rankings.add(lexical);
        }
        if (!semantic.isEmpty()) {
            rankings.add(semantic);
        }
        if (rankings.isEmpty()) {
            return List.of();
        }
        return fuse(rankings, topK, current);
    }

    /**
     * @return 查询向量；没配 provider、本次会话已降级或调用失败时返回 {@code null}
     */
    private float[] queryVector(String query) {
        if (embeddingProvider == null || !semanticUsable) {
            return null;
        }
        try {
            return embeddingProvider.embedQuery(query);
        } catch (IOException e) {
            // 端点不可用不该让提问失败：关掉语义这一路，本次会话老老实实用 BM25。
            semanticUsable = false;
            LOG.log(Level.WARNING, "语义检索不可用，本次会话退回纯 BM25：" + e.getMessage(), e);
            return null;
        }
    }

    private double[] similarities(float[] queryVector) {
        List<float[]> currentVectors = vectors;
        double[] similarities = new double[currentVectors.size()];
        for (int i = 0; i < similarities.length; i++) {
            similarities[i] = cosine(queryVector, currentVectors.get(i));
        }
        return similarities;
    }

    /**
     * 语料是否覆盖了这个问题。
     *
     * <p>两个信号取或：榜首明显高于中位数，说明语料里确实有一块紧扣问题；或者榜首本身余弦够高，
     * 说明它就是答案。两者都不满足时，语料里没有相关内容，调用方据此声明“仅通用知识”。
     */
    static boolean coversQuestion(double[] similarities) {
        double[] sorted = similarities.clone();
        Arrays.sort(sorted);
        double best = sorted[sorted.length - 1];
        double median = sorted[sorted.length / 2];
        return best >= DECISIVE_SIMILARITY || best - median >= MIN_TOP_GAP;
    }

    /**
     * 按余弦相似度排名。
     *
     * @return 命中的块号，按余弦降序；低于闸门的块不参与融合
     */
    private List<Integer> semanticRanking(double[] similarities) {
        Map<Integer, Double> scores = new HashMap<>();
        for (int i = 0; i < similarities.length; i++) {
            if (similarities[i] >= MIN_SIMILARITY) {
                scores.put(i, similarities[i]);
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
