package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

/**
 * Offline rerank evaluation: does a cross-encoder layer earn a place in retrieval?
 *
 * <p>Nothing in {@code src/main} is wired to it. The question it answers is the one the shipped
 * BM25 + embedding + RRF pipeline cannot: {@code docs/RAG-DESIGN.md} §5.1 records a single miss caused by the
 * relevance gate rejecting a covered question (the lambda/comparator one), where the embedding cosines of that
 * question and of the negatives it must still reject lie too close together to separate.
 *
 * <p>So the reranker is evaluated as a **gate replacement**, not as a reorderer: candidates come from
 * {@link RagKnowledgeBase#candidates}, which skips the gate, and the run reports whether one rerank-score
 * threshold could hold every negative while keeping every positive. A reranker sitting behind the existing gate
 * could only reorder what the gate already let through, so it could not recover the miss at all.
 *
 * <p>This prints measurements rather than asserting a threshold: a threshold would only encode today's corpus.
 * What it does assert is the validity condition of the measurement — the candidate pool must contain the
 * expected page for every covered question, or the reranked hit rate is measuring a pool that never had the
 * answer in it.
 *
 * <p>The report opens with a scorer sanity check, because a rerank score has to clear that before its numbers
 * mean anything. It is skipped unless the lecture decks and the API key are both present, like
 * {@link GoldenSetTest}.
 */
public class RerankEvaluationTest {

    private static final int TOP_K = 5;
    /** Candidates per question. Ten: the depth the design note proposes for a reranker layer. */
    private static final int POOL = 10;

    @Test
    public void measuresWhetherRerankingImprovesTheGoldenSet() throws IOException {
        assumeTrue("no lecture decks under assets/, skipping the rerank evaluation",
                Files.isDirectory(GoldenSet.ASSETS));
        TongjiEmbeddingClient embeddings = TongjiEmbeddingClient.fromEnvironment();
        assumeTrue("" + TongjiEmbeddingClient.API_KEY_ENV + " is not configured, skipping the rerank evaluation",
                embeddings != null);
        QwenRerankClient reranker = QwenRerankClient.fromEnvironment();
        assumeTrue("rerank client unavailable", reranker != null);

        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(null, embeddings);
        knowledgeBase.index(GoldenSet.corpus(GoldenSet.ASSETS));

        Report report = evaluate(knowledgeBase, reranker, GoldenSet.questions(), probeScorer(reranker));
        System.out.println(report.text());
        assertEquals("the candidate pool must contain the expected page, or the comparison is void: "
                + report.poolMisses(), List.of(), report.poolMisses());
    }

    private static Report evaluate(RagKnowledgeBase knowledgeBase, QwenRerankClient reranker,
                                   List<GoldenSet.Question> questions, ScorerSanity sanity) throws IOException {
        int positives = 0;
        int baselineHits = 0;
        int rerankedHits = 0;
        double baselineRr = 0;
        double rerankedRr = 0;
        double lowestPositiveTop = 1;
        double highestNegativeTop = 0;
        long slowestCallMs = 0;
        List<String> rows = new ArrayList<>();
        List<String> baselineMisses = new ArrayList<>();
        List<String> baselineLeaks = new ArrayList<>();
        List<String> poolMisses = new ArrayList<>();
        List<String> rankChanges = new ArrayList<>();

        for (GoldenSet.Question question : questions) {
            List<KnowledgeChunk> baseline = knowledgeBase.retrieve(question.text(), TOP_K);
            List<KnowledgeChunk> pool = knowledgeBase.candidates(question.text(), POOL);
            long startedAt = System.nanoTime();
            double[] scores = reranker.scores(question.text(), pool.stream().map(KnowledgeChunk::text).toList());
            slowestCallMs = Math.max(slowestCallMs, (System.nanoTime() - startedAt) / 1_000_000);
            List<KnowledgeChunk> reranked = QwenRerankClient.byDescendingScore(pool, scores);
            // Cut at TOP_K, so this is a page-level top-5 hit rate, the same metric as the baseline's.
            List<KnowledgeChunk> rerankedTop = reranked.subList(0, Math.min(TOP_K, reranked.size()));
            double topScore = topScore(scores);
            int poolRank = question.isNegative() ? 0 : GoldenSet.rankOf(pool, question);
            if (!question.isNegative() && poolRank == 0) {
                poolMisses.add(question.text() + " (pool: " + GoldenSet.describe(pool) + ")");
            }

            if (question.isNegative()) {
                if (!baseline.isEmpty()) {
                    baselineLeaks.add(question.text() + " -> " + GoldenSet.describe(baseline.subList(0, 1))
                            + " (rerank top score " + format(topScore) + ")");
                }
                highestNegativeTop = Math.max(highestNegativeTop, topScore);
                rows.add(row(question, "n/a", "n/a", "n/a", topScore, 0, rerankedTop));
                continue;
            }

            positives++;
            int baselineRank = GoldenSet.rankOf(baseline, question);
            int rerankedRank = GoldenSet.rankOf(rerankedTop, question);
            if (baselineRank > 0) {
                baselineHits++;
                baselineRr += 1.0 / baselineRank;
            } else {
                baselineMisses.add(question.text() + " (baseline: " + GoldenSet.describe(baseline) + ")");
            }
            if (rerankedRank > 0) {
                rerankedHits++;
                rerankedRr += 1.0 / rerankedRank;
            }
            if (baselineRank != rerankedRank) {
                rankChanges.add(question.text() + ": baseline " + label(baselineRank) + " -> reranked "
                        + label(rerankedRank));
            }
            lowestPositiveTop = Math.min(lowestPositiveTop, topScore);
            rows.add(row(question, label(baselineRank), "rank " + poolRank, label(rerankedRank), topScore,
                    scoreOfExpectedPage(pool, scores, question), rerankedTop));
        }

        return new Report(sanity, positives, baselineHits, rerankedHits, baselineRr, rerankedRr, lowestPositiveTop,
                highestNegativeTop, slowestCallMs, rows, baselineMisses, baselineLeaks, poolMisses, rankChanges);
    }

    /** Score of the expected page itself, 0 when the candidate pool does not contain it. */
    private static double scoreOfExpectedPage(List<KnowledgeChunk> pool, double[] scores,
                                              GoldenSet.Question question) {
        for (int i = 0; i < pool.size(); i++) {
            KnowledgeChunk chunk = pool.get(i);
            if (chunk.document().equals(question.document()) && chunk.pageOrScope().equals(question.page())) {
                return scores[i];
            }
        }
        return 0;
    }

    private static double topScore(double[] scores) {
        double best = 0;
        for (double score : scores) {
            best = Math.max(best, score);
        }
        return best;
    }

    private static String row(GoldenSet.Question question, String baselineRank, String poolRank,
                              String rerankedRank, double topScore, double expectedScore,
                              List<KnowledgeChunk> rerankedTop) {
        return "  %s%n    expected %s %s | baseline %s | fused pool %s | reranked %s | top score %s | "
                .formatted(question.text(), question.document(), question.page(), baselineRank, poolRank,
                        rerankedRank, format(topScore))
                + "expected page score " + format(expectedScore)
                + "\n    reranked order: " + GoldenSet.describe(rerankedTop);
    }

    /**
     * A rerank score has to pass this before it can be called a relevance score: given the query's own text and
     * two sentences with nothing to do with it, the exact match must come first.
     */
    private static ScorerSanity probeScorer(QwenRerankClient reranker) throws IOException {
        String query = "How do I assert that a method throws an exception?";
        List<String> documents = List.of(query,
                "Penguins live on the coast of Antarctica.",
                "Bananas are yellow and rich in potassium.");
        double[] scores = reranker.scores(query, documents);
        List<String> ranked = QwenRerankClient.byDescendingScore(documents, scores);
        return new ScorerSanity(query, ranked.indexOf(query) + 1, scores.length,
                Arrays.stream(scores).mapToObj(RerankEvaluationTest::format).toList());
    }

    private static String label(int rank) {
        return rank == 0 ? "miss" : "rank " + rank;
    }

    private static String format(double score) {
        return "%.3f".formatted(score);
    }

    private record ScorerSanity(String query, int exactMatchRank, int documents, List<String> scores) {

        boolean passed() {
            return exactMatchRank == 1;
        }

        String line() {
            return passed()
                    ? "scorer sanity: passed (the query's own text ranked 1 of " + documents + ")"
                    : "scorer sanity: FAILED - for \"%s\" the query's own text ranked %d of %d (scores %s), "
                            .formatted(query, exactMatchRank, documents, scores)
                            + "so these scores are not measuring relevance and nothing below them can be trusted";
        }
    }

    /**
     * @param lowestPositiveTop  smallest top rerank score among the covered questions
     * @param highestNegativeTop largest top rerank score among the uncovered questions; a threshold gate needs
     *                           {@code lowestPositiveTop > highestNegativeTop}, otherwise no single cut holds
     *                           every negative while keeping every positive
     */
    private record Report(ScorerSanity sanity, int positives, int baselineHits, int rerankedHits, double baselineRr,
                          double rerankedRr, double lowestPositiveTop, double highestNegativeTop, long slowestCallMs,
                          List<String> rows, List<String> baselineMisses, List<String> baselineLeaks,
                          List<String> poolMisses, List<String> rankChanges) {

        boolean separable() {
            return lowestPositiveTop > highestNegativeTop;
        }

        String text() {
            return """
                    %s
                    rerank evaluation: %d positives, pool top-%d, top-%d reported, gate bypassed
                    baseline (shipped gate)  page hit@%d %s   MRR %s
                    reranked                 page hit@%d %s   MRR %s
                    slowest rerank call %d ms
                    threshold separability: lowest covered top score %s, highest uncovered top score %s -> %s

                    per question:
                    %s

                    baseline misses: %s
                    baseline negative leaks: %s
                    expected page missing from the pool: %s
                    ranks that changed: %s"""
                    .formatted(sanity.line(), positives, POOL, TOP_K,
                            TOP_K, rate(baselineHits, positives), "%.2f".formatted(baselineRr / positives),
                            TOP_K, rate(rerankedHits, positives), "%.2f".formatted(rerankedRr / positives),
                            slowestCallMs, format(lowestPositiveTop), format(highestNegativeTop),
                            separable() ? "one threshold could replace the cosine gate"
                                    : "no threshold separates them, so the reranker cannot replace the gate",
                            String.join("\n", rows), baselineMisses, baselineLeaks, poolMisses, rankChanges);
        }

        private static String rate(int hits, int positives) {
            return "%.2f (%d/%d)".formatted((double) hits / positives, hits, positives);
        }

        private static String format(double score) {
            return "%.3f".formatted(score);
        }
    }
}
