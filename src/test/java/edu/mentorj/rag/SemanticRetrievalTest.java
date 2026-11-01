package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behaviour once semantic ranking joins the fusion: it recovers chunks lexical matching misses, and falls back
 * to BM25 when something breaks.
 */
public class SemanticRetrievalTest {

    private static final float[] QUERY = {1f, 0f, 0f};
    private static final float[] RELATED = {1f, 0.1f, 0f};
    private static final float[] UNRELATED = {0f, 1f, 0f};

    private final Path indexFile;

    public SemanticRetrievalTest() throws IOException {
        this.indexFile = Files.createTempDirectory("mentorj-semantic").resolve("index.json");
    }

    private static KnowledgeChunk chunk(String text, String page) {
        return new KnowledgeChunk(text, "deck.pdf", page, 0);
    }

    /** Three chunks: 0 shares terms with the query, 1 is related only semantically (completely different wording). */
    private static List<KnowledgeChunk> chunks() {
        return List.of(
                chunk("counter increment assertion", "p.1"),
                chunk("the value starts over at nothing", "p.2"),
                chunk("generic collections hold one type", "p.3"));
    }

    private static FakeEmbeddingProvider provider() {
        return FakeEmbeddingProvider.matching(
                Map.of("counter", QUERY, "starts over", RELATED), UNRELATED);
    }

    @Test
    public void semanticRankingSurfacesAChunkTheLexicalOneMissed() {
        RagKnowledgeBase withSemantics = new RagKnowledgeBase(indexFile, provider());
        withSemantics.index(chunks());
        RagKnowledgeBase lexicalOnly = new RagKnowledgeBase();
        lexicalOnly.index(chunks());

        List<KnowledgeChunk> semantic = withSemantics.retrieve("counter", 3);
        List<KnowledgeChunk> lexical = lexicalOnly.retrieve("counter", 3);

        assertEquals(1, lexical.size());
        assertEquals("p.1", lexical.get(0).pageOrScope());
        assertTrue("the semantically related chunk should be recovered",
                semantic.stream().anyMatch(chunk -> chunk.pageOrScope().equals("p.2")));
    }

    @Test
    public void fusedScoreRanksChunksFoundByBothPathsHighest() {
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(indexFile, provider());
        knowledgeBase.index(chunks());

        List<KnowledgeChunk> hits = knowledgeBase.retrieve("counter", 3);

        // p.1 matches on both paths while p.2 matches only semantically, so after normalization p.1 must score higher.
        double both = scoreOf(hits, "p.1");
        double semanticOnly = scoreOf(hits, "p.2");
        assertTrue("matching on both paths must outrank matching on one: " + both + " vs " + semanticOnly,
                both > semanticOnly);
        assertEquals(1.0, both, 1e-9);
    }

    @Test
    public void queryFailureFallsBackToLexicalWithoutLosingResults() {
        FakeEmbeddingProvider provider = provider().failingQueriesWith(new IOException("HTTP 429"));
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(indexFile, provider);
        knowledgeBase.index(chunks());

        List<KnowledgeChunk> hits = knowledgeBase.retrieve("counter", 3);

        assertFalse("a dead endpoint during a question must still yield results", hits.isEmpty());
        assertEquals("p.1", hits.get(0).pageOrScope());
    }

    @Test
    public void stopsCallingADeadQueryEndpointForTheRestOfTheSession() {
        FakeEmbeddingProvider provider = provider().failingQueriesWith(new IOException("HTTP 500"));
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(indexFile, provider);
        knowledgeBase.index(chunks());

        knowledgeBase.retrieve("counter", 1);
        knowledgeBase.retrieve("counter", 1);

        assertEquals("the second question must not wait for another timeout", 1, provider.queries().size());
    }

    @Test
    public void reimportRetriesSemanticsAfterAQueryFailure() {
        FakeEmbeddingProvider provider = provider().failingQueriesWith(new IOException("HTTP 500"));
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(indexFile, provider);
        knowledgeBase.index(chunks());
        knowledgeBase.retrieve("counter", 1);

        // A re-import must reset the "semantics unusable" switch, or one blip would degrade the session for good.
        knowledgeBase.index(chunks());
        knowledgeBase.retrieve("counter", 1);

        assertEquals(2, provider.queries().size());
    }

    @Test
    public void importWithoutVectorsStillSearchesLexically() {
        RagKnowledgeBase knowledgeBase =
                new RagKnowledgeBase(indexFile, provider().failingWith(new IOException("timeout")));

        knowledgeBase.index(chunks());

        assertEquals(3, knowledgeBase.statistics().chunks());
        assertEquals("p.1", knowledgeBase.retrieve("counter", 1).get(0).pageOrScope());
    }

    @Test
    public void indexWithoutVectorsStillAnswers() throws IOException {
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(indexFile, provider());
        knowledgeBase.index(chunks());

        // The no-key scenario: the new instance points at the same index file but has no provider at all.
        RagKnowledgeBase reloaded = new RagKnowledgeBase(indexFile);

        List<KnowledgeChunk> hits = reloaded.retrieve("counter", 3);

        assertEquals("p.1", hits.get(0).pageOrScope());
    }

    private static double scoreOf(List<KnowledgeChunk> chunks, String page) {
        return chunks.stream()
                .filter(chunk -> chunk.pageOrScope().equals(page))
                .findFirst()
                .orElseThrow()
                .score();
    }
}
