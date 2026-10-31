package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The scrolling message list. Rows track the viewport width, and new content only scrolls when the reader is
 * already at the bottom so streaming does not yank them away from an earlier answer.
 */
final class ChatTranscript extends JPanel {

    private static final int STICK_THRESHOLD = JBUI.scale(24);

    private final MarkdownRenderer renderer;
    private final Consumer<String> insertCode;
    private final MessageList list = new MessageList();
    private final JBScrollPane scroll = new JBScrollPane(list);
    private final Map<ChatMessage, MessageCard> cards = new IdentityHashMap<>();
    private final Map<ChatMessage, JPanel> rows = new IdentityHashMap<>();
    private final List<ChatMessage> messages = new ArrayList<>();

    ChatTranscript(@NotNull MarkdownRenderer renderer) {
        this(renderer, null);
    }

    ChatTranscript(@NotNull MarkdownRenderer renderer, Consumer<String> insertCode) {
        super(new BorderLayout());
        this.renderer = renderer;
        this.insertCode = insertCode;
        setOpaque(false);
        list.setBorder(JBUI.Borders.empty(6, 10));
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        add(scroll, BorderLayout.CENTER);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                list.revalidate();
            }
        });
    }

    void add(@NotNull ChatMessage message) {
        boolean stick = isStuckToBottom();
        MessageCard card = new MessageCard(message, renderer, this::contentWidth, insertCode);
        JPanel row = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setBorder(JBUI.Borders.empty(0, 0, 8, 0));
        row.add(card, message.role() == ChatMessage.Role.USER ? BorderLayout.EAST : BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        list.add(row);
        cards.put(message, card);
        rows.put(message, row);
        messages.add(message);
        list.revalidate();
        list.repaint();
        if (stick || cards.size() == 1) {
            scrollToBottom();
        }
    }

    /** Re-renders one card, used for every stream delta and when a turn finishes. */
    void refresh(@NotNull ChatMessage message) {
        MessageCard card = cards.get(message);
        if (card == null) {
            return;
        }
        boolean stick = isStuckToBottom();
        card.refresh();
        list.revalidate();
        if (stick) {
            scrollToBottom();
        }
    }

    /** Removes one turn, used when the latest answer is regenerated. */
    void remove(@NotNull ChatMessage message) {
        JPanel row = rows.remove(message);
        cards.remove(message);
        messages.remove(message);
        if (row != null) {
            list.remove(row);
            list.revalidate();
            list.repaint();
        }
    }

    void setRetryAction(@NotNull ChatMessage message, @NotNull JButton action) {
        MessageCard card = cards.get(message);
        if (card != null) {
            card.setRetryAction(action);
            list.revalidate();
        }
    }

    /** Only the newest answer keeps a regenerate button. */
    void clearRetryActions() {
        for (MessageCard card : cards.values()) {
            card.setRetryAction(null);
        }
        list.revalidate();
    }

    void clear() {
        list.removeAll();
        cards.clear();
        rows.clear();
        messages.clear();
        list.revalidate();
        list.repaint();
    }

    int messageCount() {
        return cards.size();
    }

    @NotNull
    List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    private boolean isStuckToBottom() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - STICK_THRESHOLD;
    }

    private void scrollToBottom() {
        ApplicationManager.getApplication().invokeLater(() -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        }, ModalityState.any());
    }

    private int contentWidth() {
        Insets insets = list.getInsets();
        return Math.max(220, list.getWidth() - insets.left - insets.right);
    }

    private static final class MessageList extends JPanel implements Scrollable {

        private MessageList() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return JBUI.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return visible.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
