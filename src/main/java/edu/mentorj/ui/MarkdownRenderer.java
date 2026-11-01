package edu.mentorj.ui;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Markdown to HTML for chat answers.
 *
 * <p>Raw HTML is escaped and image URLs are not loaded; fenced code is rendered by the injected painter so the
 * result follows the IDE colour scheme. The returned map links each {@code copy:<id>} hyperlink back to its code.
 */
final class MarkdownRenderer {

    @FunctionalInterface
    interface CodePainter {
        List<Run> paint(String code, String language);
    }

    /** One styled fragment of code. A {@code null} colour inherits the block's default colour. */
    record Run(String text, Color color, boolean bold, boolean italic) {
    }

    record Rendered(String html, Map<String, String> codeBlocks) {
    }

    private final CodePainter painter;
    private final String copyLabel;
    private final String insertLabel;
    private final ChatStyles.CodeStyle codeStyle;
    private final List<Extension> extensions;
    private final Parser parser;

    MarkdownRenderer(CodePainter painter, String copyLabel) {
        this(painter, copyLabel, null, ChatStyles.codeStyle());
    }

    MarkdownRenderer(CodePainter painter, String copyLabel, String insertLabel) {
        this(painter, copyLabel, insertLabel, ChatStyles.codeStyle());
    }

    MarkdownRenderer(CodePainter painter, String copyLabel, String insertLabel, ChatStyles.CodeStyle codeStyle) {
        this.painter = painter;
        this.copyLabel = copyLabel;
        this.insertLabel = insertLabel;
        this.codeStyle = codeStyle;
        this.extensions = List.of(TablesExtension.create(), StrikethroughExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
    }

    Rendered render(String markdown) {
        RenderState state = new RenderState();
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .nodeRendererFactory(context -> new Blocks(context, state))
                .build();
        String html = renderer.render(parser.parse(markdown == null ? "" : markdown));
        // Swing's HTML renderer only draws borders when the HTML 3.2 attributes are present.
        html = html.replace("<table>", "<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">");
        return new Rendered(html, Map.copyOf(state.codeBlocks));
    }

    private final class RenderState {
        private final Map<String, String> codeBlocks = new LinkedHashMap<>();
    }

    private final class Blocks implements NodeRenderer {

        private final HtmlNodeRendererContext context;
        private final RenderState state;

        private Blocks(HtmlNodeRendererContext context, RenderState state) {
            this.context = context;
            this.state = state;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class, IndentedCodeBlock.class, Image.class);
        }

        @Override
        public void render(Node node) {
            if (node instanceof FencedCodeBlock fenced) {
                renderCode(fenced.getLiteral(), fenced.getInfo());
            } else if (node instanceof IndentedCodeBlock indented) {
                renderCode(indented.getLiteral(), null);
            } else if (node instanceof Image) {
                renderImage(node);
            }
        }

        private void renderCode(String literal, String info) {
            String code = literal == null ? "" : literal;
            String language = language(info);
            String label = language.length() > 24 ? language.substring(0, 24) : language;
            String id = "code-" + state.codeBlocks.size();
            state.codeBlocks.put(id, code);
            HtmlWriter writer = context.getWriter();
            writer.raw("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"6\" bgcolor=\""
                    + codeStyle.background() + "\">");
            String links = "<a href=\"copy:" + id + "\">" + ChatStyles.escapeHtml(copyLabel) + "</a>";
            if (insertLabel != null) {
                links += " | <a href=\"insert:" + id + "\">" + ChatStyles.escapeHtml(insertLabel) + "</a>";
            }
            writer.raw("<tr><td><font color=\"" + codeStyle.label() + "\" size=\"-1\">" + ChatStyles.escapeHtml(label)
                    + "</font></td><td align=\"right\"><font size=\"-1\">" + links + "</font></td></tr>");
            writer.raw("<tr><td colspan=\"2\"><font face=\"Monospaced\" color=\"" + codeStyle.text() + "\">");
            writer.raw(paint(code, language));
            writer.raw("</font></td></tr></table>");
        }

        private void renderImage(Node image) {
            HtmlWriter writer = context.getWriter();
            writer.raw("<font color=\"" + codeStyle.label() + "\">[Image");
            Node alt = image.getFirstChild();
            if (alt != null) {
                writer.raw(": ");
                context.render(alt);
            }
            writer.raw("]</font>");
        }

        private String paint(String code, String language) {
            List<Run> runs = painter.paint(code, language);
            if (runs == null || runs.isEmpty()) {
                runs = List.of(new Run(code, null, false, false));
            }
            StringBuilder html = new StringBuilder();
            boolean lineStart = true;
            for (Run run : runs) {
                if (run == null || run.text() == null || run.text().isEmpty()) {
                    continue;
                }
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < run.text().length(); i++) {
                    char c = run.text().charAt(i);
                    if (c == '\r') {
                        continue;
                    }
                    if (c == '\n') {
                        body.append("<br>");
                        lineStart = true;
                    } else if (c == '\t') {
                        body.append(lineStart ? "&nbsp;&nbsp;&nbsp;&nbsp;" : " ");
                    } else if (c == ' ') {
                        body.append(lineStart ? "&nbsp;" : " ");
                    } else {
                        body.append(escape(c));
                        lineStart = false;
                    }
                }
                if (body.isEmpty()) {
                    continue;
                }
                if (run.color() != null) {
                    html.append("<font color=\"").append(ChatStyles.hex(run.color())).append("\">");
                }
                if (run.bold()) {
                    html.append("<b>");
                }
                if (run.italic()) {
                    html.append("<i>");
                }
                html.append(body);
                if (run.italic()) {
                    html.append("</i>");
                }
                if (run.bold()) {
                    html.append("</b>");
                }
                if (run.color() != null) {
                    html.append("</font>");
                }
            }
            return html.toString();
        }
    }

    /** Fences carry an info string such as {@code java title="Example"}; only the language matters here. */
    private static String language(String info) {
        if (info == null) {
            return "";
        }
        String value = info.strip();
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(0, space);
    }

    private static String escape(char c) {
        return switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> "&quot;";
            default -> String.valueOf(c);
        };
    }
}
