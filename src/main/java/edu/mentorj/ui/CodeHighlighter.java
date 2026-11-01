package edu.mentorj.ui;

import com.intellij.lang.Language;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Paints fenced code blocks with the IDE's own lexer and colour scheme.
 *
 * <p>Only languages that are actually installed get colours; everything else, including a failing highlighter,
 * falls back to plain monospace text so an answer never breaks because of highlighting.
 */
final class CodeHighlighter implements MarkdownRenderer.CodePainter {

    private static final int MAX_TOKENS = 20_000;

    private static final Map<String, String> LANGUAGE_IDS = Map.ofEntries(
            Map.entry("java", "JAVA"),
            Map.entry("kotlin", "kotlin"),
            Map.entry("kt", "kotlin"),
            Map.entry("xml", "XML"),
            Map.entry("html", "HTML"),
            Map.entry("json", "JSON"),
            Map.entry("sql", "SQL"),
            Map.entry("groovy", "Groovy"),
            Map.entry("gradle", "Groovy"),
            Map.entry("js", "JavaScript"),
            Map.entry("javascript", "JavaScript"),
            Map.entry("python", "Python"),
            Map.entry("sh", "Shell Script"),
            Map.entry("bash", "Shell Script"));

    private final Project project;

    CodeHighlighter(Project project) {
        this.project = project;
    }

    @Override
    public List<MarkdownRenderer.Run> paint(String code, String language) {
        if (code == null || code.isEmpty()) {
            return List.of();
        }
        String id = LANGUAGE_IDS.get(language == null ? "" : language.toLowerCase(Locale.ROOT));
        if (id == null) {
            return plain(code);
        }
        try {
            return ReadAction.compute(() -> {
                Language resolved = Language.findLanguageByID(id);
                return resolved == null ? plain(code) : highlight(code, resolved);
            });
        } catch (RuntimeException e) {
            return plain(code);
        }
    }

    private List<MarkdownRenderer.Run> highlight(String code, Language language) {
        SyntaxHighlighter highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, null);
        if (highlighter == null) {
            return plain(code);
        }
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        Lexer lexer = highlighter.getHighlightingLexer();
        List<MarkdownRenderer.Run> runs = new ArrayList<>();
        lexer.start(code);
        while (lexer.getTokenType() != null && runs.size() < MAX_TOKENS) {
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            if (end <= start) {
                break;
            }
            TextAttributes attributes = attributes(scheme, highlighter.getTokenHighlights(lexer.getTokenType()));
            int fontType = attributes == null ? Font.PLAIN : attributes.getFontType();
            runs.add(new MarkdownRenderer.Run(code.substring(start, end),
                    attributes == null ? null : attributes.getForegroundColor(),
                    (fontType & Font.BOLD) != 0,
                    (fontType & Font.ITALIC) != 0));
            lexer.advance();
        }
        return runs.isEmpty() ? plain(code) : runs;
    }

    private static TextAttributes attributes(EditorColorsScheme scheme, TextAttributesKey[] keys) {
        for (TextAttributesKey key : keys) {
            TextAttributes attributes = scheme.getAttributes(key);
            if (attributes != null) {
                return attributes;
            }
        }
        return null;
    }

    private static List<MarkdownRenderer.Run> plain(String code) {
        return List.of(new MarkdownRenderer.Run(code, null, false, false));
    }
}
