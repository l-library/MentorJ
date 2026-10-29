package edu.mentorj.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the PDF extraction behaviour the RAG pipeline depends on: media-type detection, page count,
 * and text that stays within the page it came from. Page accuracy is what makes citations
 * verifiable, so it is asserted here rather than assumed.
 *
 * <p>Fixture: {@code src/test/resources/documents/two-pages.pdf}, two pages of known text.
 */
public class PdfExtractionTest {

    private static Path fixture() {
        return Path.of(PdfExtractionTest.class.getResource("/documents/two-pages.pdf").getPath());
    }

    @Test
    public void detectsPdfFromContent() throws IOException {
        assertEquals("application/pdf", new Tika().detect(fixture()));
    }

    @Test
    public void countsPages() throws IOException {
        try (PDDocument document = Loader.loadPDF(fixture().toFile())) {
            assertEquals(2, document.getNumberOfPages());
        }
    }

    @Test
    public void extractsTextPerPage() throws IOException {
        try (PDDocument document = Loader.loadPDF(fixture().toFile())) {
            String first = textOf(document, 1);
            String second = textOf(document, 2);

            assertTrue(first, first.contains("JUnit Jupiter and Unit Tests"));
            assertTrue(first, first.contains("JUnit runs @Test methods and reports assertion failures"));

            assertTrue(second, second.contains("Counter reset semantics"));
            assertTrue(second, second.contains("reset sets the count to zero"));
        }
    }

    @Test
    public void keepsTextOfOnePageOutOfTheNext() throws IOException {
        try (PDDocument document = Loader.loadPDF(fixture().toFile())) {
            String first = textOf(document, 1);
            String second = textOf(document, 2);

            assertFalse("page 1 must not bleed into page 2", second.contains("JUnit Jupiter and Unit Tests"));
            assertFalse("page 2 must not bleed into page 1", first.contains("Counter reset semantics"));
        }
    }

    private static String textOf(PDDocument document, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(document);
    }
}
