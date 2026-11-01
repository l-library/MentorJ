package edu.mentorj.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import edu.mentorj.MentorJBundle;
import edu.mentorj.api.Citation;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionListener;
import java.util.List;

/**
 * Sources and metadata below one answer, outside the message card.
 *
 * <p>Keeping this block out of the card means the card height only depends on its markdown body; a long citation list
 * can never squeeze the answer. The block is measured by {@link ChatTranscript} at the row width.
 */
final class MessageMeta extends JPanel {

    private static final int COPIED_MILLIS = 1500;

    private final ChatMessage message;
    private final JPanel labels = new JPanel();
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JBLabel status = new JBLabel();

    private JButton retryAction;
    private String signature;

    MessageMeta(@NotNull ChatMessage message) {
        this.message = message;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(JBUI.Borders.empty(6, 10, 0, 10));
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        labels.setOpaque(false);
        labels.setAlignmentX(LEFT_ALIGNMENT);
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        status.setFont(JBUI.Fonts.smallFont());
        status.setForeground(ChatStyles.mutedText());
        if (message.role() == ChatMessage.Role.ASSISTANT
                || message.role() == ChatMessage.Role.ERROR
                || message.state() == ChatMessage.State.FAILED) {
            actions.add(actionButton(MentorJBundle.message("chat.copy"), event -> copyAnswer()));
        }
        add(labels);
        add(actions);
        refresh();
    }

    void refresh() {
        String next = message.citations() + "|" + message.generalKnowledgeOnly() + "|"
                + message.state() + "|" + message.elapsedMillis() + "|" + status.getText();
        if (!next.equals(signature)) {
            signature = next;
            rebuildLabels();
        }
        updateVisibility();
        revalidate();
        repaint();
    }

    void setRetryAction(JButton button) {
        if (retryAction != null) {
            actions.remove(retryAction);
            retryAction = null;
        }
        if (button != null) {
            retryAction = button;
            actions.add(button);
        }
        updateVisibility();
        revalidate();
        repaint();
    }

    boolean hasContent() {
        return labels.getComponentCount() > 0 || actions.getComponentCount() > 0;
    }

    private void rebuildLabels() {
        labels.removeAll();
        List<Citation> citations = message.citations();
        if (!citations.isEmpty()) {
            StringBuilder text = new StringBuilder("<html>")
                    .append(ChatStyles.escapeHtml(MentorJBundle.message("response.citationsTitle")));
            for (Citation citation : citations) {
                text.append("<br>").append(ChatStyles.escapeHtml(MentorJBundle.message(
                        "response.citationItem", citation.document(), citation.pageOrScope())));
            }
            JBLabel sources = new JBLabel(text.append("</html>").toString());
            sources.setFont(JBUI.Fonts.smallFont());
            sources.setForeground(ChatStyles.mutedText());
            align(sources);
            labels.add(sources);
        }
        if (message.generalKnowledgeOnly()) {
            JBLabel notice = new JBLabel("<html>"
                    + ChatStyles.escapeHtml(MentorJBundle.message("toolwindow.generalKnowledgeNotice"))
                    + "</html>");
            notice.setFont(JBUI.Fonts.smallFont());
            notice.setForeground(ChatStyles.mutedText());
            align(notice);
            labels.add(notice);
        }
        if (message.state() == ChatMessage.State.CANCELLED) {
            JBLabel cancelled = new JBLabel(MentorJBundle.message("chat.cancelled"));
            cancelled.setFont(JBUI.Fonts.smallFont());
            cancelled.setForeground(ChatStyles.mutedText());
            align(cancelled);
            labels.add(cancelled);
        }
        if (message.elapsedMillis() >= 0) {
            JBLabel elapsed = new JBLabel(MentorJBundle.message("chat.elapsed",
                    String.format(java.util.Locale.ROOT, "%.1f", message.elapsedMillis() / 1000.0)));
            elapsed.setFont(JBUI.Fonts.smallFont());
            elapsed.setForeground(ChatStyles.mutedText());
            align(elapsed);
            labels.add(elapsed);
        }
        if (!status.getText().isBlank()) {
            align(status);
            labels.add(status);
        }
    }

    private void updateVisibility() {
        setVisible(hasContent());
    }

    private void copyAnswer() {
        CopyPasteManager.getInstance().setContents(new StringSelection(message.text()));
        status.setText(MentorJBundle.message("chat.copied"));
        signature = null;
        refresh();
        Timer timer = new Timer(COPIED_MILLIS, event -> {
            status.setText("");
            signature = null;
            refresh();
        });
        timer.setRepeats(false);
        timer.start();
    }

    private static void align(JBLabel label) {
        label.setAlignmentX(LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
    }

    private static JButton actionButton(String text, ActionListener listener) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setForeground(ChatStyles.link());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(listener);
        return button;
    }
}
