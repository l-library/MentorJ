package edu.mentorj.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import edu.mentorj.MentorJBundle;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.text.View;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.awt.datatransfer.StringSelection;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One visible message: a tinted bubble for the user, a bordered card for the assistant, and plain text for
 * notices and errors. The card paints its own rounded background; the HTML body only handles the text.
 */
final class MessageCard extends JPanel {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private final ChatMessage message;
    private final MarkdownRenderer renderer;
    private final Consumer<String> insertCode;
    private final int padH = JBUI.scale(10);
    private final int padV = JBUI.scale(8);
    private final int arc = JBUI.scale(12);
    private final JPanel header = new JPanel(new BorderLayout(6, 0));
    private final JPanel content = new JPanel();
    private final HtmlBody body = new HtmlBody();

    private Map<String, String> codeBlocks = Map.of();

    MessageCard(@NotNull ChatMessage message, @NotNull MarkdownRenderer renderer) {
        this(message, renderer, null);
    }

    MessageCard(@NotNull ChatMessage message, @NotNull MarkdownRenderer renderer, Consumer<String> insertCode) {
        super(new BorderLayout());
        this.message = message;
        this.renderer = renderer;
        this.insertCode = insertCode;
        setOpaque(false);

        content.setOpaque(false);
        // Not JBUI.Borders: it scales what it is given, and padV/padH are already scaled, so at 150-200% UI the
        // border took twice the padding measure() accounted for. The body then laid out narrower than it was
        // measured at, and the extra wrapped line fell outside the card.
        content.setBorder(BorderFactory.createEmptyBorder(padV, padH, padV, padH));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        if (showHeader()) {
            JPanel headerPanel = createHeader();
            headerPanel.setAlignmentX(LEFT_ALIGNMENT);
            content.add(headerPanel);
            content.add(Box.createVerticalStrut(JBUI.scale(4)));
        }
        configureBody();
        body.setAlignmentX(LEFT_ALIGNMENT);
        content.add(body);
        add(content, BorderLayout.CENTER);

        refresh();
    }

    /** Re-renders the message text; call on the EDT after streaming advances. */
    void refresh() {
        MarkdownRenderer.Rendered rendered = renderer.render(message.text());
        codeBlocks = rendered.codeBlocks();
        String html = rendered.html();
        if (message.role() == ChatMessage.Role.NOTICE) {
            html = "<html><body><div align=\"center\">" + html + "</div></body></html>";
        }
        body.setForeground(foreground());
        body.setText(html);
        body.resetMeasurement();
        // BoxLayout caches size requirements; invalidate after the body changed.
        content.revalidate();
        revalidate();
        repaint();
    }

    private boolean showHeader() {
        return message.role() != ChatMessage.Role.NOTICE;
    }

    private JPanel createHeader() {
        header.setOpaque(false);
        String label = switch (message.role()) {
            case USER -> MentorJBundle.message("chat.you");
            case ASSISTANT -> MentorJBundle.message("chat.assistant");
            case ERROR -> MentorJBundle.message("chat.errorTitle");
            case NOTICE -> "";
        };
        JBLabel name = new JBLabel(label);
        name.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));
        name.setForeground(ChatStyles.text());
        JPanel west = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        west.setOpaque(false);
        if (isError()) {
            west.add(new JBLabel(AllIcons.General.Error));
        }
        west.add(name);
        JBLabel time = new JBLabel(TIME.format(message.time()));
        time.setFont(JBUI.Fonts.smallFont());
        time.setForeground(ChatStyles.mutedText());
        header.add(west, BorderLayout.WEST);
        header.add(time, BorderLayout.EAST);
        return header;
    }

    private void configureBody() {
        body.setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
        body.setEditable(false);
        body.setOpaque(false);
        body.setBorder(JBUI.Borders.empty());
        body.setFont(UIUtil.getLabelFont());
        body.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        HTMLEditorKit kit = (HTMLEditorKit) body.getEditorKit();
        kit.getStyleSheet().addRule(ChatStyles.css());
        body.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                activate(event.getDescription());
            }
        });
    }

    private void activate(String target) {
        if (target == null) {
            return;
        }
        if (target.startsWith("insert:")) {
            String code = codeBlocks.get(target.substring("insert:".length()));
            if (code != null && insertCode != null) {
                insertCode.accept(code);
            }
            return;
        }
        if (target.startsWith("copy:")) {
            String code = codeBlocks.get(target.substring("copy:".length()));
            if (code != null) {
                CopyPasteManager.getInstance().setContents(new StringSelection(code));
            }
            return;
        }
        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
            try {
                BrowserUtil.browse(target);
            } catch (RuntimeException ignored) {
                // A browser failure must not break the transcript.
            }
        }
    }

    private static final class HtmlBody extends JEditorPane {

        private int measuredWidth = -1;
        private int measuredHeight = -1;

        @Override
        public Dimension getPreferredSize() {
            if (getUI() == null || getWidth() <= 0) {
                return super.getPreferredSize();
            }
            int width = getWidth();
            if (width != measuredWidth) {
                View root = getUI().getRootView(this);
                root.setSize(width, Short.MAX_VALUE);
                measuredHeight = Math.max(1, (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS)));
                // Leave the view at the height it will be painted at. FlowView reports a preference change, which
                // reaches revalidate() and repaint(), whenever it is laid out at a height other than the one its
                // content needs; measuring at an open height and leaving it there re-queued layout and painting for
                // as long as the sidebar stayed visible.
                root.setSize(width, measuredHeight);
                measuredWidth = width;
            }
            return new Dimension(width, measuredHeight);
        }

        /** The text or the fonts changed, so the height measured for the last width is stale. */
        void resetMeasurement() {
            measuredWidth = -1;
        }

        @Override
        public void updateUI() {
            super.updateUI();
            measuredWidth = -1;
        }
    }

    private static void alignFooterLabel(JBLabel label) {
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
    }

    private boolean isError() {
        return message.role() == ChatMessage.Role.ERROR || message.state() == ChatMessage.State.FAILED;
    }

    private Color foreground() {
        if (isError()) {
            return ChatStyles.errorText();
        }
        return message.role() == ChatMessage.Role.NOTICE ? ChatStyles.mutedText() : ChatStyles.text();
    }

    private Color background() {
        if (isError()) {
            return ChatStyles.errorCard();
        }
        return switch (message.role()) {
            case USER -> ChatStyles.userBubble();
            case ASSISTANT -> ChatStyles.card();
            case NOTICE, ERROR -> null;
        };
    }

    private Color border() {
        if (isError()) {
            return ChatStyles.errorBorder();
        }
        return switch (message.role()) {
            case USER -> ChatStyles.userBorder();
            case ASSISTANT -> ChatStyles.cardBorder();
            case NOTICE, ERROR -> null;
        };
    }

    /**
     * Measures the card for a width chosen by the transcript. The body is given that width before its height is read,
     * so no stale width can leak into the layout after a resize.
     */
    Dimension measure(int available) {
        // Read the padding from the border rather than recomputing it from padH: the width the body is measured
        // at has to be the width it is laid out at, or a wrapped last line ends up outside the card.
        Insets insets = content.getInsets();
        int horizontal = insets.left + insets.right;
        int maxWidth = message.role() == ChatMessage.Role.USER
                ? Math.max(180, (int) (available * 0.82)) : available;
        int cardWidth = message.role() == ChatMessage.Role.USER
                ? Math.min(maxWidth, Math.max(160, naturalWidth() + horizontal)) : maxWidth;
        int inner = Math.max(100, cardWidth - horizontal);
        body.setSize(new Dimension(inner, Short.MAX_VALUE));
        // The body width changed, so BoxLayout's cached size requirements are stale.
        content.invalidate();
        int height = content.getPreferredSize().height;
        return new Dimension(cardWidth, Math.max(JBUI.scale(36), height));
    }

    @Override
    public Dimension getPreferredSize() {
        return measure(getWidth() > 0 ? getWidth() : 320);
    }

    private int naturalWidth() {
        FontMetrics metrics = body.getFontMetrics(body.getFont());
        int widest = 0;
        for (String line : message.text().split("\n", -1)) {
            widest = Math.max(widest, metrics.stringWidth(line));
        }
        return widest;
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Color fill = background();
        if (fill != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            Color stroke = border();
            if (stroke != null) {
                g2.setColor(stroke);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g2.dispose();
        }
        super.paintComponent(g);
    }
}
