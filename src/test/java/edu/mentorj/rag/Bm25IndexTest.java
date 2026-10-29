package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Bm25IndexTest {

    private static KnowledgeChunk chunk(String text, String page) {
        return new KnowledgeChunk(text, "deck.pdf", page, 0);
    }

    @Test
    public void ranksTheChunkThatTalksAboutTheQueryFirst() {
        Bm25Index index = Bm25Index.of(List.of(
                chunk("Exceptions interrupt the normal flow of a program", "p.1"),
                chunk("JUnit runs @Test methods and reports assertion failures", "p.2"),
                chunk("Generics let a collection hold one type", "p.3")));

        assertEquals(List.of(1), chunksOf(index.search("assertion failure", 1)));
    }

    private static List<Integer> chunksOf(List<Bm25Index.Hit> hits) {
        return hits.stream().map(Bm25Index.Hit::chunk).toList();
    }

    @Test
    public void reportsHowMuchOfTheQueryEachChunkCovers() {
        Bm25Index index = Bm25Index.of(List.of(
                chunk("JUnit runs @Test methods and reports assertion failures", "p.1"),
                chunk("Generics let a collection hold one type", "p.2")));

        List<Bm25Index.Hit> hits = index.search("assertion failures type", 5);

        // "assertion / failures / type" 三个词，两块各覆盖 2 个和 1 个。
        assertEquals(2, hits.size());
        assertEquals(0, hits.get(0).chunk());
        assertEquals(2 / 3.0, hits.get(0).coverage(), 1e-9);
        assertEquals(1, hits.get(1).chunk());
        assertEquals(1 / 3.0, hits.get(1).coverage(), 1e-9);
    }

    @Test
    public void returnsNothingWhenNoTermMatches() {
        Bm25Index index = Bm25Index.of(List.of(chunk("JUnit runs @Test methods", "p.1")));

        assertEquals(List.of(), chunksOf(index.search("kafka partitioning", 5)));
    }

    @Test
    public void rareTermOutranksATermThatAppearsEverywhere() {
        Bm25Index index = Bm25Index.of(List.of(
                chunk("java java java java", "p.1"),
                chunk("java collections framework", "p.2"),
                chunk("java reflection api", "p.3")));

        // "reflection" 只出现在一个块里，idf 高；"java" 出现在所有块里，idf 接近 0。
        assertEquals(2, chunksOf(index.search("java reflection", 1)).get(0).intValue());
    }

    @Test
    public void respectsLimit() {
        Bm25Index index = Bm25Index.of(List.of(
                chunk("counter increment", "p.1"),
                chunk("counter reset", "p.2"),
                chunk("counter constructor", "p.3")));

        assertEquals(2, index.search("counter", 2).size());
    }

    @Test
    public void matchesCamelCaseInQueryAgainstSplitCodeText() {
        Bm25Index index = Bm25Index.of(List.of(
                chunk("void incrementAddsOne() { counter.increment(); }", "p.1"),
                chunk("Generics let a collection hold one type", "p.2")));

        assertEquals(0, chunksOf(index.search("incrementAddsOne", 1)).get(0).intValue());
    }

    @Test
    public void matchesChineseQueryAgainstChineseChunk() {
        Bm25Index index = Bm25Index.of(List.of(
                chunk("异常处理：try catch finally", "p.1"),
                chunk("Generics let a collection hold one type", "p.2")));

        assertTrue(chunksOf(index.search("异常", 1)).contains(0));
    }
}
