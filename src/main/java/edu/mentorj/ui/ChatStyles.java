package edu.mentorj.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.UIUtil;

import java.awt.Color;

/** Theme-aware colours, fonts and HTML rules shared by every chat card. */
final class ChatStyles {

    private static final JBColor CARD = new JBColor(0xFFFFFF, 0x2B2D30);
    private static final JBColor CARD_BORDER = new JBColor(0xDFE1E5, 0x393B40);
    private static final JBColor USER = new JBColor(0xEAF2FF, 0x2F3A50);
    private static final JBColor USER_BORDER = new JBColor(0xC9DBF7, 0x3F4F6B);
    private static final JBColor ERROR = new JBColor(0xFFF3F3, 0x3A2A2A);
    private static final JBColor ERROR_BORDER = new JBColor(0xE3A9A9, 0x8C4A4A);
    private static final JBColor ERROR_TEXT = new JBColor(0xA5182F, 0xFF9A9A);
    private static final JBColor CODE = new JBColor(0xF7F8FA, 0x1E1F22);
    private static final JBColor LINK = new JBColor(0x2F6FEB, 0x589DF6);

    private ChatStyles() {
    }

    static Color card() {
        return CARD;
    }

    static Color cardBorder() {
        return CARD_BORDER;
    }

    static Color userBubble() {
        return USER;
    }

    static Color userBorder() {
        return USER_BORDER;
    }

    static Color errorCard() {
        return ERROR;
    }

    static Color errorBorder() {
        return ERROR_BORDER;
    }

    static Color errorText() {
        return ERROR_TEXT;
    }

    static Color link() {
        return LINK;
    }

    static Color mutedText() {
        return UIUtil.getContextHelpForeground();
    }

    static Color text() {
        return UIUtil.getLabelForeground();
    }

    static String hex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    static String escapeHtml(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** Rules Swing's HTML renderer understands; rounded corners are painted by the card itself. */
    static String css() {
        return "p { margin-top: 0; margin-bottom: 6px; }\n"
                + "h1 { font-size: 18px; margin-top: 10px; margin-bottom: 4px; }\n"
                + "h2 { font-size: 16px; margin-top: 10px; margin-bottom: 4px; }\n"
                + "h3 { font-size: 14px; margin-top: 8px; margin-bottom: 4px; }\n"
                + "h4 { font-size: 13px; margin-top: 8px; margin-bottom: 4px; }\n"
                + "ul { margin-top: 0; margin-bottom: 6px; margin-left: 18px; }\n"
                + "ol { margin-top: 0; margin-bottom: 6px; margin-left: 18px; }\n"
                + "li { margin-bottom: 2px; }\n"
                + "blockquote { margin-left: 8px; margin-right: 8px; color: " + hex(mutedText()) + "; }\n"
                + "code { font-family: Monospaced; }\n"
                + "a { color: " + hex(LINK) + "; }\n"
                + "table { margin-top: 4px; margin-bottom: 8px; }\n"
                + "th, td { padding: 3px; }\n";
    }

    /** Colours embedded in fenced code blocks, because Swing ignores most CSS on tables. */
    record CodeStyle(String background, String label, String text) {
    }

    static CodeStyle codeStyle() {
        return new CodeStyle(hex(CODE), hex(mutedText()), hex(text()));
    }
}
