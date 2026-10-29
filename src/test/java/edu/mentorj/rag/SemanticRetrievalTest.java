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

/** 语义排名接入融合后的行为：能捞回词法漏掉的块，出问题时退回 BM25。 */
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

    /** 三块：0 与查询有词面重合，1 只在语义上相关（用词完全不同）。 */
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
        assertTrue("语义相关块应被捞回", semantic.stream().anyMatch(chunk -> chunk.pageOrScope().equals("p.2")));
    }

    @Test
    public void fusedScoreRanksChunksFoundByBothPathsHighest() {
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(indexFile, provider());
        knowledgeBase.index(chunks());

        List<KnowledgeChunk> hits = knowledgeBase.retrieve("counter", 3);

        // p.1 两路都命中，p.2 只有语义命中，所以归一化后 p.1 必须更高。
        double both = scoreOf(hits, "p.1");
        double semanticOnly = scoreOf(hits, "p.2");
        assertTrue("两路都命中应高于只被一路命中：" + both + " vs " + semanticOnly, both > semanticOnly);
        assertEquals(1.0, both, 1e-9);
    }

    @Test
    public void queryFailureFallsBackToLexicalWithoutLosingResults() {
        FakeEmbeddingProvider provider = provider().failingQueriesWith(new IOException("HTTP 429"));
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(indexFile, provider);
        knowledgeBase.index(chunks());

        List<KnowledgeChunk> hits = knowledgeBase.retrieve("counter", 3);

        assertFalse("提问时端点挂了也要有结果", hits.isEmpty());
        assertEquals("p.1", hits.get(0).pageOrScope());
    }

    @Test
    public void stopsCallingADeadQueryEndpointForTheRestOfTheSession() {
        FakeEmbeddingProvider provider = provider().failingQueriesWith(new IOException("HTTP 500"));
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(indexFile, provider);
        knowledgeBase.index(chunks());

        knowledgeBase.retrieve("counter", 1);
        knowledgeBase.retrieve("counter", 1);

        assertEquals("第二次提问不该再等一次超时", 1, provider.queries().size());
    }

    @Test
    public void reimportRetriesSemanticsAfterAQueryFailure() {
        FakeEmbeddingProvider provider = provider().failingQueriesWith(new IOException("HTTP 500"));
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(indexFile, provider);
        knowledgeBase.index(chunks());
        knowledgeBase.retrieve("counter", 1);

        // 重新导入应当把“语义不可用”的开关重置，否则一次抖动就永久降级。
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

        // 没有 key 的场景：新实例拿的是同一个索引文件，但根本没有 provider。
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
