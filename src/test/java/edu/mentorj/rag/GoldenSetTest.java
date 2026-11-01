package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Retrieval quality regression: run the golden set against the real lecture decks and report the hit rates.
 *
 * <p>Why it is skipped by default: the decks live under {@code assets/} and are not in the repo, and the
 * semantic path also needs an API key, so missing either one makes the test unreproducible. CI skips it; with
 * a local environment configured it is the yardstick for before and after a retrieval change.
 *
 * <p>The thresholds are the measured values with headroom left below them, not guesses: a change that turns
 * this red really did regress.
 */
public class GoldenSetTest {

    private static final int TOP_K = 5;
    /** Page-level hit rate floor. Measured 0.93, capped here with headroom, so turning this red means a regression. */
    private static final double MIN_PAGE_HIT_RATE = 0.85;

    @Test
    public void answersTheGoldenSet() throws IOException {
        assumeTrue("no lecture decks under assets/, skipping the golden set", Files.isDirectory(GoldenSet.ASSETS));
        TongjiEmbeddingClient provider = TongjiEmbeddingClient.fromEnvironment();
        assumeTrue("" + TongjiEmbeddingClient.API_KEY_ENV + " is not configured, skipping the semantic evaluation",
                provider != null);

        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(null, provider);
        knowledgeBase.index(GoldenSet.corpus(GoldenSet.ASSETS));
        GoldenResult result = evaluate(knowledgeBase, GoldenSet.questions());

        System.out.println(result.report());
        assertTrue("page-level hit rate: " + result.report(), result.pageHitRate() >= MIN_PAGE_HIT_RATE);
        assertEquals("negatives must all return empty results: " + result.report(), List.of(),
                result.leakedNegatives());
    }

    private static GoldenResult evaluate(RagKnowledgeBase knowledgeBase, List<GoldenSet.Question> questions) {
        int pageHits = 0;
        int documentHits = 0;
        int positives = 0;
        double reciprocalRanks = 0;
        List<String> misses = new ArrayList<>();
        List<String> leaked = new ArrayList<>();
        for (GoldenSet.Question question : questions) {
            List<KnowledgeChunk> hits = knowledgeBase.retrieve(question.text(), TOP_K);
            if (question.isNegative()) {
                if (!hits.isEmpty()) {
                    leaked.add(question.text() + " -> " + hits.get(0).document() + " " + hits.get(0).pageOrScope());
                }
                continue;
            }
            positives++;
            int rank = GoldenSet.rankOf(hits, question);
            if (rank > 0) {
                pageHits++;
                reciprocalRanks += 1.0 / rank;
            } else {
                misses.add(question.text() + " (expected " + question.document() + " " + question.page() + ", "
                        + "got " + GoldenSet.describe(hits) + ")");
            }
            if (hits.stream().anyMatch(hit -> hit.document().equals(question.document()))) {
                documentHits++;
            }
        }
        return new GoldenResult(positives, pageHits, documentHits, reciprocalRanks / Math.max(1, positives),
                misses, leaked);
    }

    private record GoldenResult(int positives, int pageHits, int documentHits, double mrr,
                                List<String> misses, List<String> leakedNegatives) {

        double pageHitRate() {
            return positives == 0 ? 0 : (double) pageHits / positives;
        }

        double documentHitRate() {
            return positives == 0 ? 0 : (double) documentHits / positives;
        }

        String report() {
            return """
                    golden: %d positives / %d negatives
                     page-level top-%d hit rate %.2f (%d/%d)
                     document-level hit rate %.2f
                     MRR %.2f
                     misses: %s
                     leaked negatives: %s"""
                    .formatted(positives, leakedNegatives.size(), TOP_K, pageHitRate(), pageHits, positives,
                            documentHitRate(), mrr, misses, leakedNegatives);
        }
    }
}
