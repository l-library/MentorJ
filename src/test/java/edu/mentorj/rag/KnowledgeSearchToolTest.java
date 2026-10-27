package edu.mentorj.rag;

import edu.mentorj.api.Citation;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.Tool;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class KnowledgeSearchToolTest {

    @Test
    public void returnsContentAndDistinctVerifiableSources() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool((query, topK) -> {
            assertEquals("JUnit", query);
            assertEquals(5, topK);
            return List.of(new KnowledgeChunk("Use assertEquals", "JUnit.pdf", "3-4", 1),
                    new KnowledgeChunk("Test exceptions", "JUnit.pdf", "3-4", 0.9));
        });
        Tool.ExecutionResult output = tool.executeWithSources("{\"query\":\" JUnit \"}");
        assertTrue(output.text().contains("Use assertEquals"));
        assertTrue(output.text().contains("Test exceptions"));
        assertTrue(output.text().contains("JUnit.pdf"));
        assertTrue(output.text().contains("3-4"));
        assertEquals(List.of(new Citation("JUnit.pdf", "3-4")), output.citations());
    }

    @Test
    public void rejectsNonStringEmptyAndOversizedQueriesBeforeRetrieval() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool((query, topK) -> {
            throw new AssertionError("Invalid query reached knowledge base");
        });
        for (String json : List.of("{}", "[]", "{\"query\":true}", "{\"query\":42}", "{\"query\":null}",
                "{\"query\":\" \"}", "{\"query\":\"" + "x".repeat(4001) + "\"}")) {
            Tool.ExecutionResult output = tool.executeWithSources(json);
            assertTrue(output.text().startsWith("tool_error:"));
            assertTrue(output.citations().isEmpty());
        }
    }

    @Test
    public void distinguishesEmptySearchFromMissingSourceMetadata() {
        KnowledgeSearchTool empty = new KnowledgeSearchTool((query, topK) -> List.of());
        assertEquals("no results", empty.execute("{\"query\":\"JUnit\"}"));
        KnowledgeSearchTool incomplete = new KnowledgeSearchTool((query, topK) -> List.of(
                new KnowledgeChunk("assertEquals", "JUnit.pdf", null, 1)));
        Tool.ExecutionResult output = incomplete.executeWithSources("{\"query\":\"JUnit\"}");
        assertTrue(output.text().startsWith("tool_error:"));
        assertTrue(output.citations().isEmpty());
    }
}
