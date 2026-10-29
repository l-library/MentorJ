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
 * <p>检索分两路，排名用 RRF（reciprocal rank fusion）融合：BM25 词法、embedding 语义。当前只有
 * BM25 一路，语义那一路接入后加进 {@link #rankings} 即可，融合与打分口径不变。
 *
 * <p>{@code KnowledgeChunk.score} 是融合分并归一化到 (0, 1]：在每一路都排第一的块得 1.0。
 * 之所以不直接暴露 BM25 原始分，是它没有上界（可能是 0.5 也可能是 18），界面无法展示，也没法和
 * 语义分放在一起比较。
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

    private volatile List<KnowledgeChunk> chunks = List.of();
    private volatile Bm25Index bm25 = Bm25Index.of(List.of());
    private volatile Instant indexedAt;
    /** 只在 {@link #ensureLoaded()} 与写入方法里读写，由 this 的锁保护。 */
    private boolean loaded;

    /** 纯内存知识库，退出即丢，用于测试与没有项目路径的场景。 */
    public RagKnowledgeBase() {
        this(null);
    }

    /**
     * @param indexFile 索引文件路径；{@code null} 表示只存内存
     */
    public RagKnowledgeBase(Path indexFile) {
        this.indexFile = indexFile;
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
        return fuse(rankings, topK, current);
    }

    /** 用新知识块整体替换现有索引，并写盘。写盘失败时降级为本次会话内存有效。 */
    public synchronized void index(List<KnowledgeChunk> newChunks) {
        List<KnowledgeChunk> copy = List.copyOf(newChunks);
        Bm25Index newIndex = Bm25Index.of(copy);
        Instant now = Instant.now();
        if (indexFile != null) {
            try {
                IndexStore.write(indexFile, copy, now);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "索引写盘失败，本次会话仍可用，重启后需重新导入：" + indexFile, e);
            }
        }
        this.chunks = copy;
        this.bm25 = newIndex;
        this.indexedAt = now;
        this.loaded = true;
    }

    /** 清空索引并删掉磁盘上的索引文件。 */
    public synchronized void clear() {
        this.chunks = List.of();
        this.bm25 = Bm25Index.of(List.of());
        this.indexedAt = null;
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
