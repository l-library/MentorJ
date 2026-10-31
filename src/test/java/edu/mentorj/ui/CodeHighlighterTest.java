package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

/** The highlighter must never drop or reorder code, even when a language or lexer is unavailable. */
public class CodeHighlighterTest extends BasePlatformTestCase {

    public void testPaintedRunsReproduceTheCodeExactly() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            CodeHighlighter highlighter = new CodeHighlighter(getProject());
            String code = "class Example {\n  int value = 1;\n}";
            List<MarkdownRenderer.Run> runs = highlighter.paint(code, "java");
            assertFalse(runs.isEmpty());
            assertEquals(code, String.join("", runs.stream().map(MarkdownRenderer.Run::text).toList()));
            assertTrue(runs.stream().allMatch(run -> run.text() != null));
        });
    }

    public void testUnknownLanguageFallsBackToPlainText() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            List<MarkdownRenderer.Run> runs = new CodeHighlighter(getProject()).paint("hello", "brainfuck");
            assertEquals(1, runs.size());
            assertEquals("hello", runs.get(0).text());
            assertNull(runs.get(0).color());
        });
    }
}
