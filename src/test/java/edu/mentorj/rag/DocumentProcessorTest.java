package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DocumentProcessor} chunking and cleaning rules. The fixture is a two-page PDF with known text; page
 * number cleaning and the re-chunking boundaries are covered with constructed input, so the test does not
 * depend on the lecture decks (they are not in the repo and CI does not have them).
 */
public class DocumentProcessorTest {

    private final DocumentProcessor processor = new DocumentProcessor();

    @Test
    public void producesOneChunkPerPageWithCitation() throws Exception {
        List<KnowledgeChunk> chunks = processor.process(fixture());

        assertEquals(2, chunks.size());
        assertEquals("p.1", chunks.get(0).pageOrScope());
        assertEquals("p.2", chunks.get(1).pageOrScope());
        assertEquals("two-pages.pdf", chunks.get(0).document());
        assertEquals("two-pages.pdf", chunks.get(1).document());
        assertEquals(0.0, chunks.get(0).score(), 0.0);
    }

    @Test
    public void keepsPageText() throws Exception {
        List<KnowledgeChunk> chunks = processor.process(fixture());

        assertTrue(chunks.get(0).text().contains("JUnit Jupiter and Unit Tests"));
        assertTrue(chunks.get(0).text().contains("JUnit runs @Test methods and reports assertion failures"));
        assertTrue(chunks.get(1).text().contains("reset sets the count to zero"));
    }

    @Test
    public void dropsStandaloneAndFooterPageNumbers() throws Exception {
        List<KnowledgeChunk> chunks = processor.process(fixture());

        assertFalse("a page number alone on a line must be removed",
                List.of(chunks.get(0).text().split("\n")).contains("1"));
        assertFalse("a page number in the footer must be removed",
                chunks.get(1).text().contains("Tools and Scenarios 2"));
        assertTrue("the footer text itself is kept", chunks.get(1).text().contains("Tools and Scenarios"));
    }

    @Test
    public void rejectsNonPdf() throws IOException {
        Path text = Files.createTempFile("mentorj", ".txt");
        Files.writeString(text, "not a pdf");
        try {
            processor.process(text);
            fail("a non-PDF must fail");
        } catch (DocumentProcessingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("unsupported file type"));
        } finally {
            Files.deleteIfExists(text);
        }
    }

    @Test
    public void stripsPageNumberInAllThreeLayouts() {
        List<String> lines = List.of("3", "3Overview", "Tools and Scenarios 3", "Slide body text");

        assertEquals(List.of("Overview", "Tools and Scenarios", "Slide body text"),
                DocumentProcessor.cleanPage(lines, 3));
    }

    @Test
    public void keepsContentNumbersThatDoNotMatchThePage() {
        List<String> lines = List.of("4 Accept code only with JUnit evidence", "Java 21", "2");

        assertEquals("a number different from the current page is left alone",
                List.of("4 Accept code only with JUnit evidence", "Java 21", "2"),
                DocumentProcessor.cleanPage(lines, 3));
    }

    @Test
    public void keepsShortPageAsOneChunk() {
        assertEquals(List.of("Line one\nLine two"), DocumentProcessor.splitPage(List.of("Line one", "Line two")));
    }

    @Test
    public void splitsLongPageAndRepeatsTheLastLine() {
        String line = "x".repeat(100);
        List<String> page = java.util.Collections.nCopies(10, line);

        List<String> chunks = DocumentProcessor.splitPage(page);

        assertEquals(2, chunks.size());
        assertEquals(7, chunks.get(0).split("\n").length);
        assertTrue("the previous chunk's last line carries into the next chunk", chunks.get(1).startsWith(line + "\n"));
        assertEquals("the overlap must not add a blank line", 4, chunks.get(1).split("\n").length);
    }

    @Test
    public void hardSplitsASingleOverlongLine() {
        List<String> chunks = DocumentProcessor.splitPage(List.of("y".repeat(1000)));

        assertEquals(2, chunks.size());
        assertEquals(800, chunks.get(0).length());
        assertEquals(200, chunks.get(1).length());
    }

    private static Path fixture() {
        // Same as PdfExtractionTest: on Windows getPath() carries a leading slash, so go through the URI.
        URL resource = Objects.requireNonNull(DocumentProcessorTest.class.getResource("/documents/two-pages.pdf"));
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
