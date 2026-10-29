package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 相关性闸门：语料没覆盖的问题必须返回空表，否则 agent 会拿无关页面当引用。
 *
 * <p>用假 provider 精确控制相似度，因此断言的是闸门逻辑本身，而不是模型的表现。
 */
public class RelevanceGateTest {

    private static final float[] QUERY = {1f, 0f};

    private static KnowledgeChunk chunk(String text, String page) {
        return new KnowledgeChunk(text, "deck.pdf", page, 0);
    }

    /** 每一块与查询的余弦由给定值决定。 */
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
        // 三块一模一样地“中等相关”，说明谁都不是答案。
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
        // 差值只有 0.25，没到 0.30，但榜首 0.70 已经够定论。
        List<KnowledgeChunk> hits = withSimilarities(0.45, 0.70, 0.45).retrieve("q", 3);

        assertFalse(hits.isEmpty());
        assertEquals("p.2", hits.get(0).pageOrScope());
    }

    @Test
    public void borderlineTopWithFlatRestIsRejected() {
        // 这是实测里最难分的一档：0.55 上下、没有明显领先，语料其实没讲这个。
        assertEquals(List.of(), withSimilarities(0.52, 0.55, 0.50).retrieve("q", 3));
    }

    @Test
    public void trailingOffTopicChunksDoNotBecomeCitations() {
        List<KnowledgeChunk> hits = withSimilarities(0.75, 0.30, 0.20).retrieve("q", 3);

        assertEquals("只有过线的块才该进引用", 1, hits.size());
        assertEquals("p.1", hits.get(0).pageOrScope());
    }

    @Test
    public void gapThresholdHoldsOnTheMeasuredBoundary() {
        // 实测（218 块）：正例 max-median 最低 0.319，负例最高 0.280，阈值取 0.30。
        assertTrue("正例边界：差 0.32", RagKnowledgeBase.coversQuestion(
                new double[]{0.55, 0.23, 0.23, 0.23, 0.23}));
        assertFalse("负例边界：差 0.265", RagKnowledgeBase.coversQuestion(
                new double[]{0.545, 0.28, 0.28, 0.28, 0.28}));
    }

    @Test
    public void aHighTopScoreDoesNotNeedAGap() {
        // 语料整体都相关时差值会很小，此时靠榜首余弦定论。
        assertTrue(RagKnowledgeBase.coversQuestion(new double[]{0.62, 0.60, 0.61}));
    }
}
