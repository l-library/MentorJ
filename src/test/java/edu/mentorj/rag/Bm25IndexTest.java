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

        assertEquals(List.of(1), index.search("assertion failure", 1));
    }

    @Test
    public void returnsNothingWhenNoTermMatches() {
        Bm25Index index = Bm25Index.of(List.of(chunk("JUnit runs @Test methods", "p.1")));

        assertEquals(List.of(), index.search("kafka partitioning", 5));
    }

    @Test
    public void rareTermOutranksATermThatAppearsEverywhere() {
        Bm25Index index = Bm25Index.of(List.of(
                chunk("java java java java", "p.1"),
                chunk("java collections framework", "p.2"),
                chunk("java reflection api", "p.3")));

        // "reflection" 只出现在一个块里，idf 高；"java" 出现在所有块里，idf 接近 0。
        assertEquals(Integer.valueOf(2), index.search("java reflection", 1).get(0));
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

        assertEquals(Integer.valueOf(0), index.search("incrementAddsOne", 1).get(0));
    }

    @Test
    public void matchesChineseQueryAgainstChineseChunk() {
        Bm25Index index = Bm25Index.of(List.of(
                chunk("异常处理：try catch finally", "p.1"),
                chunk("Generics let a collection hold one type", "p.2")));

        assertTrue(index.search("异常", 1).contains(0));
    }
}
