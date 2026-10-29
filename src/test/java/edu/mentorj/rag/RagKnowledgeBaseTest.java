package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RagKnowledgeBaseTest {

    private static RagKnowledgeBase knowledgeBase() {
        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase();
        knowledgeBase.index(List.of(
                new KnowledgeChunk("Exceptions interrupt the normal flow of a program", "Lec-3.1.pdf", "p.2", 0),
                new KnowledgeChunk("JUnit runs @Test methods and reports assertion failures", "Lec-3.4.pdf", "p.2", 0),
                new KnowledgeChunk("Generics let a collection hold one type", "Lec-3.2.pdf", "p.5", 0)));
        return knowledgeBase;
    }

    @Test
    public void returnsTheRelevantChunkWithItsCitation() {
        List<KnowledgeChunk> chunks = knowledgeBase().retrieve("assertion failures in JUnit", 3);

        assertEquals("Lec-3.4.pdf", chunks.get(0).document());
        assertEquals("p.2", chunks.get(0).pageOrScope());
    }

    @Test
    public void topHitScoresOneAndTheRestBelow() {
        List<KnowledgeChunk> chunks = knowledgeBase().retrieve("test type", 3);

        assertEquals(1.0, chunks.get(0).score(), 1e-9);
        for (KnowledgeChunk chunk : chunks) {
            assertTrue("score 落在 (0, 1]：" + chunk.score(), chunk.score() > 0 && chunk.score() <= 1.0);
        }
    }

    @Test
    public void returnsEmptyWhenNothingMatches() {
        assertEquals(List.of(), knowledgeBase().retrieve("kafka partitioning strategy", 5));
    }

    @Test
    public void returnsEmptyForBlankQueryOrEmptyBase() {
        assertEquals(List.of(), knowledgeBase().retrieve("   ", 5));
        assertEquals(List.of(), knowledgeBase().retrieve(null, 5));
        assertEquals(List.of(), new RagKnowledgeBase().retrieve("junit", 5));
    }

    @Test
    public void respectsTopK() {
        assertEquals(1, knowledgeBase().retrieve("test type", 1).size());
        assertEquals(2, knowledgeBase().retrieve("test type", 10).size());
    }

    @Test
    public void dropsChunksThatCoverTooLittleOfTheQuery() {
        // 三个词只命中一个：按闸门规则不该当引用材料拿去用。
        assertEquals(List.of(), knowledgeBase().retrieve("kafka partitioning exception", 5));
    }
}
