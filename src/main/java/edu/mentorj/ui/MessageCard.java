package edu.mentorj.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import edu.mentorj.MentorJBundle;
import edu.mentorj.api.Citation;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.event.HyperlinkEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionListener;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * One visible message: a tinted bubble for the user, a bordered card for the assistant, and plain text for
 * notices and errors. The card paints its own rounded background; the HTML body only handles the text.
 */
final class MessageCard extends JPanel {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final int COPIED_MILLIS = 1500;

    private final ChatMessage message;
    private final MarkdownRenderer renderer;
    private final IntSupplier widthSupplier;
    private final Consumer<String> insertCode;
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final int padH = JBUI.scale(10);
    private final int padV = JBUI.scale(8);
    private final int arc = JBUI.scale(12);
    private final JPanel header = new JPanel(new BorderLayout(6, 0));
    private final JPanel footer = new JPanel();
    private final JEditorPane body = new JEditorPane();
    private final JBLabel status = new JBLabel();

    private Map<String, String> codeBlocks = Map.of();
    private Dimension preferred;
    private int cachedWidth = -1;
    private String footerSignature;
    private JButton retryAction;

    MessageCard(@NotNull ChatMessage message, @NotNull MarkdownRenderer renderer,
                @NotNull IntSupplier widthSupplier) {
        this(message, renderer, widthSupplier, null);
    }

    MessageCard(@NotNull ChatMessage message, @NotNull MarkdownRenderer renderer,
                @NotNull IntSupplier widthSupplier, Consumer<String> insertCode) {
        super(new BorderLayout());
        this.message = message;
        this.renderer = renderer;
        this.widthSupplier = widthSupplier;
        this.insertCode = insertCode;
        setOpaque(false);

        JPanel content = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        content.setOpaque(false);
        content.setBorder(JBUI.Borders.empty(padV, padH));
        if (showHeader()) {
            content.add(createHeader(), BorderLayout.NORTH);
        }
        configureBody();
        content.add(body, BorderLayout.CENTER);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setOpaque(false);
        actions.setOpaque(false);
        actions.setVisible(false);
        if (message.role() == ChatMessage.Role.ASSISTANT) {
            actions.add(actionButton(MentorJBundle.message("chat.copy"), event -> copyAnswer()));
            actions.setVisible(true);
        }
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setOpaque(false);
        south.add(footer);
        south.add(actions);
        content.add(south, BorderLayout.SOUTH);
        add(content, BorderLayout.CENTER);

        status.setFont(JBUI.Fonts.smallFont());
        status.setForeground(ChatStyles.mutedText());
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
        refreshFooter();
        preferred = null;
        cachedWidth = -1;
        revalidate();
        repaint();
    }

    private boolean showHeader() {
        return message.role() == ChatMessage.Role.USER || message.role() == ChatMessage.Role.ASSISTANT;
    }

    private JPanel createHeader() {
        header.setOpaque(false);
        JBLabel name = new JBLabel(message.role() == ChatMessage.Role.USER
                ? MentorJBundle.message("chat.you") : MentorJBundle.message("chat.assistant"));
        name.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD));
        name.setForeground(ChatStyles.text());
        JBLabel time = new JBLabel(TIME.format(message.time()));
        time.setFont(JBUI.Fonts.smallFont());
        time.setForeground(ChatStyles.mutedText());
        header.add(name, BorderLayout.WEST);
        header.add(time, BorderLayout.EAST);
        return header;
    }

    private void configureBody() {
        body.setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
        body.setEditable(false);
        body.setOpaque(false);
        body.setBorder(JBUI.Borders.empty());
        body.setFont(UIUtil.getLabelFont());
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
                showCopied();
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

    /** Adds the regenerate button to the newest answer; older cards have it removed by the transcript. */
    void setRetryAction(JButton button) {
        if (retryAction != null) {
            actions.remove(retryAction);
            retryAction = null;
        }
        if (button != null) {
            retryAction = button;
            actions.add(button);
        }
        actions.setVisible(actions.getComponentCount() > 0);
        preferred = null;
        cachedWidth = -1;
        revalidate();
        repaint();
    }

    private void copyAnswer() {
        CopyPasteManager.getInstance().setContents(new StringSelection(message.text()));
        showCopied();
    }

    private static JButton actionButton(String text, ActionListener listener) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setForeground(ChatStyles.link());
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        button.addActionListener(listener);
        return button;
    }

    private void showCopied() {
        status.setText(MentorJBundle.message("chat.copied"));
        footerSignature = null;
        refreshFooter();
        Timer timer = new Timer(COPIED_MILLIS, event -> {
            status.setText("");
            footerSignature = null;
            refreshFooter();
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void refreshFooter() {
        String signature = message.citations() + "|" + message.generalKnowledgeOnly() + "|"
                + message.state() + "|" + status.getText();
        if (signature.equals(footerSignature)) {
            return;
        }
        footerSignature = signature;
        footer.removeAll();
        List<Citation> citations = message.citations();
        if (!citations.isEmpty()) {
            StringBuilder text = new StringBuilder("<html>")
                    .append(ChatStyles.escapeHtml(MentorJBundle.message("response.citationsTitle")));
            for (Citation citation : citations) {
                text.append("<br>").append(ChatStyles.escapeHtml(MentorJBundle.message(
                        "response.citationItem", citation.document(), citation.pageOrScope())));
            }
            JBLabel label = new JBLabel(text.append("</html>").toString());
            label.setFont(JBUI.Fonts.smallFont());
            label.setForeground(ChatStyles.mutedText());
            footer.add(label);
        }
        if (message.generalKnowledgeOnly()) {
            JBLabel notice = new JBLabel("<html>"
                    + ChatStyles.escapeHtml(MentorJBundle.message("toolwindow.generalKnowledgeNotice"))
                    + "</html>");
            notice.setFont(JBUI.Fonts.smallFont());
            notice.setForeground(ChatStyles.mutedText());
            footer.add(notice);
        }
        if (message.state() == ChatMessage.State.CANCELLED) {
            JBLabel cancelled = new JBLabel(MentorJBundle.message("chat.cancelled"));
            cancelled.setFont(JBUI.Fonts.smallFont());
            cancelled.setForeground(ChatStyles.mutedText());
            footer.add(cancelled);
        }
        if (!status.getText().isBlank()) {
            footer.add(status);
        }
        footer.revalidate();
        footer.repaint();
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

    @Override
    public Dimension getPreferredSize() {
        int available = Math.max(220, widthSupplier.getAsInt());
        if (preferred != null && cachedWidth == available) {
            return preferred;
        }
        int maxWidth = message.role() == ChatMessage.Role.USER
                ? Math.max(180, (int) (available * 0.82)) : available;
        int cardWidth = message.role() == ChatMessage.Role.USER
                ? Math.min(maxWidth, Math.max(160, naturalWidth() + 2 * padH)) : maxWidth;
        int inner = Math.max(100, cardWidth - 2 * padH);
        body.setSize(new Dimension(inner, Short.MAX_VALUE));
        int height = body.getPreferredSize().height + 2 * padV;
        if (showHeader()) {
            height += header.getPreferredSize().height + JBUI.scale(4);
        }
        if (footer.getComponentCount() > 0) {
            height += footer.getPreferredSize().height + JBUI.scale(4);
        }
        if (actions.isVisible() && actions.getComponentCount() > 0) {
            height += actions.getPreferredSize().height + JBUI.scale(4);
        }
        preferred = new Dimension(cardWidth, Math.max(JBUI.scale(36), height));
        cachedWidth = available;
        return preferred;
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
