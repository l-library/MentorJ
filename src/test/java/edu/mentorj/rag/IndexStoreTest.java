package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IndexStoreTest {

    private final Path directory = Files.createTempDirectory("mentorj-index");

    public IndexStoreTest() throws IOException {
    }

    @Test
    public void roundTripsChunks() throws IOException {
        Path file = directory.resolve("mentorj-index.json");
        List<KnowledgeChunk> chunks = List.of(
                new KnowledgeChunk("A unit test checks a small behavior", "Lec-3.4-JUnit.pdf", "p.2", 0),
                new KnowledgeChunk("reset sets the count to zero", "Lec-3.4-JUnit.pdf", "p.3", 0));

        IndexStore.write(file, chunks, Instant.parse("2026-10-30T08:00:00Z"));
        IndexStore.Loaded read = IndexStore.read(file);

        assertEquals(Instant.parse("2026-10-30T08:00:00Z"), read.generatedAt());
        assertEquals(2, read.chunks().size());
        assertEquals("Lec-3.4-JUnit.pdf", read.chunks().get(0).document());
        assertEquals("p.2", read.chunks().get(0).pageOrScope());
        assertEquals("A unit test checks a small behavior", read.chunks().get(0).text());
        assertEquals("p.3", read.chunks().get(1).pageOrScope());
    }

    @Test
    public void writesTheSchemaVersionAndTimestamp() throws IOException {
        Path file = directory.resolve("mentorj-index.json");

        IndexStore.write(file, List.of(), Instant.parse("2026-10-30T08:00:00Z"));

        String json = Files.readString(file);
        assertTrue(json, json.contains("\"version\": " + IndexStore.VERSION));
        assertTrue(json, json.contains("2026-10-30T08:00:00Z"));
    }

    @Test
    public void missingFileReadsAsEmpty() throws IOException {
        assertEquals(List.of(), IndexStore.read(directory.resolve("absent.json")).chunks());
        assertNull(IndexStore.read(directory.resolve("absent.json")).generatedAt());
    }

    @Test
    public void unknownVersionIsDiscarded() throws IOException {
        Path file = directory.resolve("mentorj-index.json");
        Files.writeString(file, "{\"version\": 99, \"chunks\": [{\"text\": \"x\"}]}");

        assertEquals(List.of(), IndexStore.read(file).chunks());
    }

    @Test(expected = IOException.class)
    public void corruptFileIsReported() throws IOException {
        Path file = directory.resolve("mentorj-index.json");
        Files.writeString(file, "{not json");

        IndexStore.read(file);
    }
}
