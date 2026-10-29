package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link DocumentProcessor} 的切块与清洗规则。夹具是两页已知文本的 PDF；页码清洗与二次切分的
 * 边界用构造输入覆盖，不依赖课件（课件不进仓库，CI 里没有）。
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

        assertFalse("独占一行的页码必须删掉", List.of(chunks.get(0).text().split("\n")).contains("1"));
        assertFalse("页脚里的页码必须删掉", chunks.get(1).text().contains("Tools and Scenarios 2"));
        assertTrue("页脚的文字部分保留", chunks.get(1).text().contains("Tools and Scenarios"));
    }

    @Test
    public void rejectsNonPdf() throws IOException {
        Path text = Files.createTempFile("mentorj", ".txt");
        Files.writeString(text, "not a pdf");
        try {
            processor.process(text);
            fail("非 PDF 必须失败");
        } catch (DocumentProcessingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("不支持的文件类型"));
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

        assertEquals("与当前页号不同的数字不动",
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
        assertTrue("上一块的末行带进下一块", chunks.get(1).startsWith(line + "\n"));
        assertEquals("重叠段不能多出空行", 4, chunks.get(1).split("\n").length);
    }

    @Test
    public void hardSplitsASingleOverlongLine() {
        List<String> chunks = DocumentProcessor.splitPage(List.of("y".repeat(1000)));

        assertEquals(2, chunks.size());
        assertEquals(800, chunks.get(0).length());
        assertEquals(200, chunks.get(1).length());
    }

    private static Path fixture() {
        return Path.of(DocumentProcessorTest.class.getResource("/documents/two-pages.pdf").getPath());
    }
}
