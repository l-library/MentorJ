package edu.mentorj.ui;

import org.junit.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer(
            (code, language) -> List.of(new MarkdownRenderer.Run(code, Color.RED, false, false)),
            "复制", new ChatStyles.CodeStyle("#f7f8fa", "#888888", "#111111"));

    @Test
    public void rendersInlineMarkdownAndGfmTables() {
        MarkdownRenderer.Rendered rendered = renderer.render("""
                **bold** *italic* ~~gone~~ and `code`

                | a | b |
                | --- | --- |
                | 1 | 2 |
                """);
        String html = rendered.html();
        assertTrue(html.contains("<strong>bold</strong>"));
        assertTrue(html.contains("<em>italic</em>"));
        assertTrue(html.contains("<del>gone</del>"));
        assertTrue(html.contains("<code>code</code>"));
        assertTrue(html.contains("<table border=\"1\""));
        assertTrue(html.contains("<td>1</td>"));
        assertTrue(rendered.codeBlocks().isEmpty());
    }

    @Test
    public void rendersFencedCodeWithCopyTargetAndWrappedIndentation() {
        MarkdownRenderer.Rendered rendered = renderer.render("""
                ```java
                class A {
                  int x = 1;
                }
                ```
                """);
        String html = rendered.html();
        assertTrue(html.contains("copy:code-0"));
        assertTrue(html.contains("java"));
        assertTrue(html.contains("&nbsp;&nbsp;int x"));
        assertTrue(html.contains("<br>"));
        assertEquals("class A {\n  int x = 1;\n}\n", rendered.codeBlocks().get("code-0"));
    }

    @Test
    public void keepsSeveralCodeBlocksApartAndFallsBackWithoutLanguage() {
        MarkdownRenderer.Rendered rendered = renderer.render("""
                ```
                one
                ```

                ```xml
                <a/>
                ```
                """);
        assertEquals(2, rendered.codeBlocks().size());
        assertEquals("one\n", rendered.codeBlocks().get("code-0"));
        assertEquals("<a/>\n", rendered.codeBlocks().get("code-1"));
        assertTrue(rendered.html().contains("copy:code-1"));
    }

    @Test
    public void escapesRawHtmlAndReplacesImagesWithAltText() {
        String html = renderer.render(
                "<script>alert(1)</script>\n\n![diagram](https://example.com/tracker.png)").html();
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<img"));
        assertTrue(html.contains("[图片：diagram]"));
    }

    @Test
    public void sanitizesUnsafeLinkDestinations() {
        String html = renderer.render("[click](javascript:alert(1))").html();
        assertFalse(html.contains("javascript:"));
    }

    @Test
    public void escapesCodeForTheFallbackPainter() {
        MarkdownRenderer plain = new MarkdownRenderer((code, language) -> List.of(),
                "copy", new ChatStyles.CodeStyle("#fff", "#888", "#111"));
        String html = plain.render("```java\nif (a < b) { c(\"x\"); }\n```").html();
        assertTrue(html.contains("if (a &lt; b) { c(&quot;x&quot;); }"));
    }
}
