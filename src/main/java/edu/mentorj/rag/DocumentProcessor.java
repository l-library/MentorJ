package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.Tika;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Course material processing: parse a PDF and chunk it page by page, keeping the source document and page.
 *
 * <p>Page numbers come from the PDF page order and match the page numbers printed in the slide footer, so
 * citations can be checked page by page. Chunking respects page boundaries: a chunk never spans pages, and
 * the page text is split further only when a single page exceeds {@value #MAX_CHUNK_CHARS} characters.
 *
 * <p>PDFBox extracts the text page by page rather than Tika: Tika's {@code PDFParser} emits one plain-text
 * run without page boundaries, so "page N" is unavailable, and the page number is exactly what a citation
 * needs. Tika only detects the file type.
 */
public final class DocumentProcessor {

    private static final String PDF = "application/pdf";
    private static final int MAX_CHUNK_CHARS = 800;
    /** When a page is split again, the previous chunk's last line carries into the next chunk up to this
     * length, so sentences stay intact. */
    private static final int OVERLAP_CHARS = 120;
    private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");

    private final Tika tika = new Tika();

    /**
     * Parse one document.
     *
     * @return chunks in page order with {@code score} 0 (relevance is computed during retrieval); empty pages
     *         produce no chunk
     * @throws DocumentProcessingException the file type is unsupported or reading failed
     */
    public List<KnowledgeChunk> process(Path document) throws DocumentProcessingException {
        String name = document.getFileName().toString();
        requirePdf(document, name);
        try (PDDocument pdf = Loader.loadPDF(document.toFile())) {
            List<KnowledgeChunk> chunks = new ArrayList<>();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                for (String text : splitPage(cleanPage(linesOf(pdf, page), page))) {
                    chunks.add(new KnowledgeChunk(text, name, "p." + page, 0));
                }
            }
            return chunks;
        } catch (IOException e) {
            throw new DocumentProcessingException(name + ": read failed (" + e.getMessage() + ")", e);
        }
    }

    private void requirePdf(Path document, String name) throws DocumentProcessingException {
        String type;
        try {
            type = tika.detect(document);
        } catch (IOException e) {
            throw new DocumentProcessingException(name + ": cannot be read (" + e.getMessage() + ")", e);
        }
        if (!PDF.equals(type)) {
            throw new DocumentProcessingException(name + ": unsupported file type " + type + ", only PDF is supported");
        }
    }

    private static List<String> linesOf(PDDocument pdf, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return List.of(stripper.getText(pdf).split("\\R"));
    }

    /**
     * Strip page numbers and drop lines that hold nothing after cleaning.
     *
     * <p>The slides place page numbers three ways, all of them seen in the actual decks: alone on a line
     * ({@code 3}), glued to the front of a heading ({@code 2Overview}), and after a footer
     * ({@code Tools and Scenarios 6}). Only a number equal to the current page number is removed, so
     * list numbers in the body (such as {@code 4 Accept code only with ...}) are never deleted by mistake.
     */
    static List<String> cleanPage(List<String> lines, int page) {
        List<String> cleaned = new ArrayList<>();
        for (String line : lines) {
            String withoutNumber = stripPageNumber(line.strip(), page);
            if (HAS_WORD.matcher(withoutNumber).find()) {
                cleaned.add(withoutNumber);
            }
        }
        return cleaned;
    }

    private static String stripPageNumber(String line, int page) {
        String number = Integer.toString(page);
        if (line.equals(number)) {
            return "";
        }
        if (line.length() > number.length() && line.startsWith(number)
                && !Character.isDigit(line.charAt(number.length()))) {
            return line.substring(number.length()).strip();
        }
        if (line.endsWith(" " + number)) {
            return line.substring(0, line.length() - number.length()).strip();
        }
        return line;
    }

    /**
     * Split one page's lines into text blocks of at most {@value #MAX_CHUNK_CHARS} characters.
     *
     * <p>A page under the limit comes back unchanged as a single chunk, which covers almost every slide
     * page. A single line longer than the limit is cut hard by character count.
     */
    static List<String> splitPage(List<String> lines) {
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (String line : lines) {
            if (line.length() > MAX_CHUNK_CHARS) {
                flush(chunks, chunk);
                for (int start = 0; start < line.length(); start += MAX_CHUNK_CHARS) {
                    int end = Math.min(line.length(), start + MAX_CHUNK_CHARS);
                    chunks.add(line.substring(start, end));
                }
                continue;
            }
            if (chunk.length() > 0 && chunk.length() + 1 + line.length() > MAX_CHUNK_CHARS) {
                String tail = lastLine(chunk);
                flush(chunks, chunk);
                if (tail.length() <= OVERLAP_CHARS) {
                    chunk.append(tail);
                }
            }
            if (chunk.length() > 0) {
                chunk.append('\n');
            }
            chunk.append(line);
        }
        flush(chunks, chunk);
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder chunk) {
        if (chunk.length() > 0) {
            chunks.add(chunk.toString().strip());
            chunk.setLength(0);
        }
    }

    private static String lastLine(StringBuilder chunk) {
        int lastBreak = chunk.lastIndexOf("\n");
        return lastBreak < 0 ? chunk.toString() : chunk.substring(lastBreak + 1);
    }
}
