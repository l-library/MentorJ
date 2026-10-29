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
 * 课程材料文档处理：解析 PDF 并按页切块，保留来源文档与页码。
 *
 * <p>页码来自 PDF 页序，与课件页脚印的页码一致，因此引用可以逐页核对。切块以页为界，
 * 一个知识块永远不跨页；只有单页文本超过 {@value #MAX_CHUNK_CHARS} 字符时才在页内二次切分。
 *
 * <p>PDFBox 负责逐页抽文本而不是 Tika：Tika 的 {@code PDFParser} 输出一段无页边界的纯文本，
 * 拿不到“第 N 页”，而页码正是引用的前提。Tika 只用来判断文件类型。
 */
public final class DocumentProcessor {

    private static final String PDF = "application/pdf";
    private static final int MAX_CHUNK_CHARS = 800;
    /** 页内二次切分时，上一块最后一行不超过这个长度就带进下一块，避免句子被切断。 */
    private static final int OVERLAP_CHARS = 120;
    private static final Pattern HAS_WORD = Pattern.compile("[\\p{L}\\p{N}]");

    private final Tika tika = new Tika();

    /**
     * 解析一个文档。
     *
     * @return 按页顺序排列的知识块，{@code score} 为 0（相关性由检索阶段给出）；空页不产生知识块
     * @throws DocumentProcessingException 文件类型不受支持，或读取失败
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
            throw new DocumentProcessingException(name + ": 读取失败（" + e.getMessage() + "）", e);
        }
    }

    private void requirePdf(Path document, String name) throws DocumentProcessingException {
        String type;
        try {
            type = tika.detect(document);
        } catch (IOException e) {
            throw new DocumentProcessingException(name + ": 无法读取（" + e.getMessage() + "）", e);
        }
        if (!PDF.equals(type)) {
            throw new DocumentProcessingException(name + ": 不支持的文件类型 " + type + "，只支持 PDF");
        }
    }

    private static List<String> linesOf(PDDocument pdf, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return List.of(stripper.getText(pdf).split("\\R"));
    }

    /**
     * 去掉页码并丢掉清洗后没有内容的行。
     *
     * <p>课件页码有三种排布，都已在实际课件里出现：独占一行（{@code 3}）、粘在标题前
     * （{@code 2Overview}）、跟在页脚后（{@code Tools and Scenarios 6}）。只删与当前页号相同的数字，
     * 因此正文里的序号（如 {@code 4 Accept code only with ...}）不会被误删。
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
     * 把一页的行切成不超过 {@value #MAX_CHUNK_CHARS} 字符的文本块。
     *
     * <p>整页不超过上限时原样返回一块，绝大多数课件页都是这种情况。单行本身超长时按字符硬切。
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
