package edu.mentorj.rag;

import edu.mentorj.api.ImportResult;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.ProgressListener;
import edu.mentorj.api.Statistics;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RagDocumentLibraryTest {

    private final Path directory = Files.createTempDirectory("mentorj-library");
    private final Path indexFile = directory.resolve(".idea").resolve("mentorj-index.json");

    public RagDocumentLibraryTest() throws IOException {
    }

    @Test
    public void importsDocumentsAndReportsStatistics() throws IOException {
        Path first = copyFixture("a.pdf");
        Path second = copyFixture("b.pdf");

        ImportResult result = library().importDocuments(List.of(first, second), null);

        assertEquals(List.of("a.pdf", "b.pdf"), result.documents());
        assertEquals(4, result.chunks());
        assertEquals(List.of(), result.failures());
        Statistics statistics = library().statistics();
        assertEquals(4, statistics.chunks());
        assertNotNull(statistics.indexedAt());
    }

    @Test
    public void keepsGoingWhenOneDocumentFails() throws IOException {
        Path good = copyFixture("good.pdf");
        Path bad = directory.resolve("notes.txt");
        Files.writeString(bad, "not a pdf");

        ImportResult result = library().importDocuments(List.of(bad, good), null);

        assertEquals(List.of("good.pdf"), result.documents());
        assertEquals(2, result.chunks());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0), result.failures().get(0).startsWith("notes.txt:"));
    }

    @Test
    public void rebuiltIndexReplacesTheOldOne() throws IOException {
        RagDocumentLibrary library = library();
        library.importDocuments(List.of(copyFixture("a.pdf"), copyFixture("b.pdf")), null);

        ImportResult result = library.importDocuments(List.of(copyFixture("c.pdf")), null);

        assertEquals(2, result.chunks());
        assertEquals(List.of("c.pdf"), library.statistics().documents());
    }

    @Test
    public void indexSurvivesANewInstance() throws IOException {
        library().importDocuments(List.of(copyFixture("a.pdf")), null);

        RagKnowledgeBase restored = new RagKnowledgeBase(indexFile);
        List<KnowledgeChunk> hits = restored.retrieve("counter reset", 3);

        assertFalse("重启后应能检索到", hits.isEmpty());
        assertEquals("a.pdf", hits.get(0).document());
        assertEquals("p.2", hits.get(0).pageOrScope());
        assertNotNull(restored.statistics().indexedAt());
    }

    @Test
    public void clearEmptiesMemoryAndDisk() throws IOException {
        RagKnowledgeBase base = new RagKnowledgeBase(indexFile);
        RagDocumentLibrary library = new RagDocumentLibrary(base, new DocumentProcessor());
        library.importDocuments(List.of(copyFixture("a.pdf")), null);

        library.clear();

        assertEquals(List.of(), library.statistics().documents());
        assertEquals(0, library.statistics().chunks());
        assertNull(library.statistics().indexedAt());
        assertFalse(Files.exists(indexFile));
        assertEquals(List.of(), base.retrieve("counter", 3));
    }

    @Test
    public void reportsProgressUntilTheEnd() throws IOException {
        List<String> events = new ArrayList<>();
        ProgressListener progress = (done, total, message) -> events.add(done + "/" + total + " " + message);

        library().importDocuments(List.of(copyFixture("a.pdf"), copyFixture("b.pdf")), progress);

        assertEquals(List.of("0/2 a.pdf", "1/2 b.pdf", "2/2 "), events);
    }

    @Test
    public void importingNothingClearsTheIndex() throws IOException {
        RagDocumentLibrary library = library();
        library.importDocuments(List.of(copyFixture("a.pdf")), null);

        ImportResult result = library.importDocuments(List.of(), null);

        assertEquals(0, result.chunks());
        assertEquals(0, library.statistics().chunks());
    }

    @Test
    public void worksWithoutAnIndexFile() throws IOException {
        RagDocumentLibrary memoryOnly = libraryOfMemory();

        ImportResult result = memoryOnly.importDocuments(List.of(copyFixture("a.pdf")), null);

        assertEquals(2, result.chunks());
        assertEquals(2, memoryOnly.statistics().chunks());
        assertFalse(Files.exists(indexFile));
    }

    @Test
    public void aCorruptIndexFileReadsAsEmptyInsteadOfFailing() throws IOException {
        Files.createDirectories(indexFile.getParent());
        Files.writeString(indexFile, "{not json");

        RagKnowledgeBase base = new RagKnowledgeBase(indexFile);

        assertEquals(List.of(), base.retrieve("counter", 3));
        assertEquals(0, base.statistics().chunks());
    }

    private RagDocumentLibrary library() {
        return new RagDocumentLibrary(new RagKnowledgeBase(indexFile), new DocumentProcessor());
    }

    private RagDocumentLibrary libraryOfMemory() {
        return new RagDocumentLibrary(new RagKnowledgeBase(), new DocumentProcessor());
    }

    private Path copyFixture(String name) throws IOException {
        Path target = directory.resolve(name);
        try (InputStream in = RagDocumentLibraryTest.class.getResourceAsStream("/documents/two-pages.pdf")) {
            Files.copy(in, target);
        }
        return target;
    }
}
