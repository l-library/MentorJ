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
 * Course material knowledge base: stores the chunks DocumentProcessor produced and serves them to the agent.
 *
 * <p>Retrieval has two paths whose rankings are fused with RRF (reciprocal rank fusion): BM25 lexical and
 * embedding semantic. The semantic path needs vectors in the index and a configured API key; when either is
 * missing it falls back to pure BM25 automatically, and citations and page numbers stay just as valid.
 *
 * <p>{@code KnowledgeChunk.score} is the fused score normalized into (0, 1]: a chunk ranked first by both
 * paths scores 1.0. The raw BM25 score is deliberately not exposed, because it has no upper bound (it may be
 * 0.5 or 18), so the UI could not display it and it could not be compared with a semantic score.
 *
 * <p>With an index file path, the index loads from disk on **first use**, never in the constructor: the
 * project-level service is created on the EDT, so reading a file in the constructor means putting disk I/O
 * on the EDT.
 */
public final class RagKnowledgeBase implements KnowledgeBase {

    private static final Logger LOG = Logger.getLogger(RagKnowledgeBase.class.getName());

    private static final int RRF_K = 60;
    /** Takes the top ranks from each path into the fusion; chunks ranked lower are not worth the context. */
    private static final int RRF_DEPTH = 20;
    /*
     * Relevance gate. Without it, a single matching term returns results, so content the slides never cover
     * still picks up a fake citation, while the assignment requires stating plainly that only general
     * knowledge is used when retrieval finds nothing.
     *
     * The thresholds are measured (218 chunks, 18 queries, see docs/RAG-DESIGN.md §5.2). Why not gate on an
     * absolute cosine: different wordings of the same question sit only 0.91-0.98 apart, and in practice
     * "How do I read a file line by line?" scores 0.545 while its all-lowercase version scores 0.423, so a
     * wording change alone pushes a query across an absolute threshold. The gate uses a **relative signal**
     * instead: the gap between the top score and the median, with true positives bottoming out at 0.319 and
     * negatives peaking at 0.280, so the two do not overlap.
     */
    private static final double MIN_TOP_GAP = 0.30;
    /** When the top cosine is high enough, the corpus is taken to cover the question and no gap is required. */
    private static final double DECISIVE_SIMILARITY = 0.60;
    /** Lowest cosine for a chunk to enter the citation list, which trims off-topic chunks ranked below the top one. */
    private static final double MIN_SIMILARITY = 0.45;
    /** Fallback with no vectors: at least half of the query terms must appear in the same chunk. */
    private static final double MIN_COVERAGE = 0.50;

    private final Path indexFile;
    private final EmbeddingProvider embeddingProvider;

    private volatile List<KnowledgeChunk> chunks = List.of();
    private volatile List<float[]> vectors = List.of();
    private volatile Bm25Index bm25 = Bm25Index.of(List.of());
    private volatile Instant indexedAt;
    /**
     * Whether semantic retrieval is still trustworthy. After the endpoint fails, no further question waits for
     * a timeout and this session falls back to BM25; a re-import turns it back on.
     */
    private volatile boolean semanticUsable = true;
    /** Read and written only in {@link #ensureLoaded()} and the write methods, guarded by this object's lock. */
    private boolean loaded;

    /** Pure in-memory knowledge base, lost on exit, used by tests and by scenarios without a project path. */
    public RagKnowledgeBase() {
        this(null, null);
    }

    public RagKnowledgeBase(Path indexFile) {
        this(indexFile, null);
    }

    /**
     * @param indexFile         index file path; {@code null} means in-memory only
     * @param embeddingProvider embedding implementation; {@code null} means BM25 only
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
     * @return the query vector; {@code null} when no provider is configured, this session already degraded, or
     *         the call failed
     */
    private float[] queryVector(String query) {
        if (embeddingProvider == null || !semanticUsable) {
            return null;
        }
        try {
            return embeddingProvider.embedQuery(query);
        } catch (IOException e) {
            // An unavailable endpoint must not fail the question: turn off the semantic path, use BM25 this session.
            semanticUsable = false;
            LOG.log(Level.WARNING,
                    "Semantic retrieval unavailable, falling back to BM25 for this session: " + e.getMessage(), e);
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
     * Whether the corpus covers this question.
     *
     * <p>Two signals combined with OR: the top score sits well above the median, meaning one chunk in the
     * corpus really is on point; or the top cosine is high enough on its own, meaning it is the answer. When
     * neither holds, the corpus has nothing relevant, and the caller states that only general knowledge is used.
     */
    static boolean coversQuestion(double[] similarities) {
        double[] sorted = similarities.clone();
        Arrays.sort(sorted);
        double best = sorted[sorted.length - 1];
        double median = sorted[sorted.length / 2];
        return best >= DECISIVE_SIMILARITY || best - median >= MIN_TOP_GAP;
    }

    /**
     * Rank by cosine similarity.
     *
     * @return matched chunk indexes in descending cosine order; chunks below the gate do not join the fusion
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

    /** Vectors from different implementations are not necessarily normalized, so divide by the magnitudes. */
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

    /** Replace the index wholesale with the new chunks and write it to disk. A failed write degrades to
     * memory-only for this session. */
    public synchronized void index(List<KnowledgeChunk> newChunks) {
        List<KnowledgeChunk> copy = List.copyOf(newChunks);
        List<float[]> newVectors = embed(copy);
        Bm25Index newIndex = Bm25Index.of(copy);
        Instant now = Instant.now();
        if (indexFile != null) {
            try {
                IndexStore.write(indexFile, copy, newVectors, now);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Writing the index failed; it stays usable for this session but must be "
                        + "re-imported after a restart: " + indexFile, e);
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
     * Vectorize chunk by chunk.
     *
     * <p>On failure it returns an empty list instead of throwing: the imported documents are still worth
     * keeping, and retrieval degrading to BM25 beats wasting the whole import.
     */
    private List<float[]> embed(List<KnowledgeChunk> chunks) {
        if (embeddingProvider == null || chunks.isEmpty()) {
            return List.of();
        }
        List<String> texts = chunks.stream().map(KnowledgeChunk::text).toList();
        try {
            List<float[]> newVectors = embeddingProvider.embed(texts);
            if (newVectors.size() != chunks.size()) {
                LOG.warning("Vector count does not match chunk count (" + newVectors.size() + " vs "
                        + chunks.size() + "); building the lexical index only");
                return List.of();
            }
            return List.copyOf(newVectors);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Vectorization failed; building the lexical index only: " + e.getMessage(), e);
            return List.of();
        }
    }

    /** Clear the index and delete the index file on disk. */
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
                LOG.log(Level.WARNING, "Deleting the index file failed: " + indexFile, e);
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
            // Treat a corrupt index as "no index": the user can simply re-import, and the sidebar must still open.
            LOG.log(Level.WARNING, "Reading the index failed; treating it as empty: " + indexFile, e);
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
