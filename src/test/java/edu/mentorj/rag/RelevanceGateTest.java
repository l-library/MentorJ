package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Relevance gate: a question the corpus does not cover must return an empty list, or the agent will cite an
 * unrelated page.
 *
 * <p>A fake provider sets the similarities exactly, so the assertions target the gate logic itself rather than
 * how the model behaves.
 */
public class RelevanceGateTest {

    private static final float[] QUERY = {1f, 0f};

    private static KnowledgeChunk chunk(String text, String page) {
        return new KnowledgeChunk(text, "deck.pdf", page, 0);
    }

    /** The cosine between each chunk and the query is fixed by the given values. */
    private static RagKnowledgeBase withSimilarities(double... similarities) {
        FakeEmbeddingProvider provider = new FakeEmbeddingProvider(2, text -> switch (text) {
            case "q" -> QUERY;
            case "a" -> vector(similarities[0], Math.sqrt(1 - similarities[0] * similarities[0]));
            case "b" -> vector(similarities[1], Math.sqrt(1 - similarities[1] * similarities[1]));
            default -> vector(similarities[2], Math.sqrt(1 - similarities[2] * similarities[2]));
        });
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(null, provider);
        knowledgeBase.index(List.of(chunk("a", "p.1"), chunk("b", "p.2"), chunk("c", "p.3")));
        return knowledgeBase;
    }

    private static float[] vector(double x, double y) {
        return new float[]{(float) x, (float) y};
    }

    @Test
    public void everythingEquallySimilarMeansTheCorpusDoesNotCoverIt() {
        // All three chunks are equally "moderately relevant", which means none of them is the answer.
        assertEquals(List.of(), withSimilarities(0.45, 0.45, 0.45).retrieve("q", 3));
    }

    @Test
    public void oneChunkClearlyAboveTheRestIsCoverage() {
        List<KnowledgeChunk> hits = withSimilarities(0.40, 0.75, 0.42).retrieve("q", 3);

        assertEquals(1, hits.size());
        assertEquals("p.2", hits.get(0).pageOrScope());
    }

    @Test
    public void aHighTopScoreIsEnoughEvenWhenNothingStandsOut() {
        // The gap is only 0.25, short of 0.30, but the top score of 0.70 already settles it.
        List<KnowledgeChunk> hits = withSimilarities(0.45, 0.70, 0.45).retrieve("q", 3);

        assertFalse(hits.isEmpty());
        assertEquals("p.2", hits.get(0).pageOrScope());
    }

    @Test
    public void borderlineTopWithFlatRestIsRejected() {
        // The hardest band to separate in practice: around 0.55 with no clear leader, and the corpus never covers this.
        assertEquals(List.of(), withSimilarities(0.52, 0.55, 0.50).retrieve("q", 3));
    }

    @Test
    public void trailingOffTopicChunksDoNotBecomeCitations() {
        List<KnowledgeChunk> hits = withSimilarities(0.75, 0.30, 0.20).retrieve("q", 3);

        assertEquals("only chunks past the line belong in the citations", 1, hits.size());
        assertEquals("p.1", hits.get(0).pageOrScope());
    }

    @Test
    public void gapThresholdHoldsOnTheMeasuredBoundary() {
        // Measured (218 chunks): the max-median gap bottoms out at 0.319 for positives and peaks at 0.280
        // for negatives, so the threshold sits at 0.30.
        assertTrue("positive boundary: gap 0.32", RagKnowledgeBase.coversQuestion(
                new double[]{0.55, 0.23, 0.23, 0.23, 0.23}));
        assertFalse("negative boundary: gap 0.265", RagKnowledgeBase.coversQuestion(
                new double[]{0.545, 0.28, 0.28, 0.28, 0.28}));
    }

    @Test
    public void aRejectedQuestionStillOffersItsBestChunkAsUnverifiedContext() {
        // The borderline band, where the gate decides the corpus does not cover the question. The ranking still
        // has an order, so the agent gets the best chunk as unverified context instead of nothing.
        RagKnowledgeBase knowledgeBase = withSimilarities(0.52, 0.55, 0.50);

        assertEquals(List.of(), knowledgeBase.retrieve("q", 3));
        List<KnowledgeChunk> context = knowledgeBase.lowConfidenceContext("q", 1);
        assertEquals(1, context.size());
        assertEquals("p.2", context.get(0).pageOrScope());
    }

    @Test
    public void unverifiedContextIsEmptyWhenTheCorpusHasNothingToOffer() {
        // Only the gate's answer changes on rejection; a query that matches nothing still returns nothing.
        RagKnowledgeBase empty = new RagKnowledgeBase();

        assertEquals(List.of(), empty.lowConfidenceContext("q", 1));
        assertEquals(List.of(), empty.lowConfidenceContext(" ", 1));
    }

    @Test
    public void aHighTopScoreDoesNotNeedAGap() {
        // When the whole corpus is relevant the gap stays small, and the top cosine settles it.
        assertTrue(RagKnowledgeBase.coversQuestion(new double[]{0.62, 0.60, 0.61}));
    }
}
