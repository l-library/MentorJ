package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import edu.mentorj.MentorJBundle;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
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
 *
 * <p>The list lays its rows out itself: every row is measured after its width is known, in the same layout pass.
 * A Swing layout manager would ask for a height first and apply the width later, which used to clip the last lines
 * of an answer while the sidebar border was dragged.
 */
final class ChatTranscript extends JPanel {

    private static final int STICK_THRESHOLD = JBUI.scale(24);
    private static final int ROW_GAP = JBUI.scale(8);

    private final MarkdownRenderer renderer;
    private final Consumer<String> insertCode;
    private final MessageList list = new MessageList();
    private final JBScrollPane scroll = new JBScrollPane(list);
    private final JButton jumpToBottom = new JButton(MentorJBundle.message("chat.toBottom"));
    private final JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    private final Map<ChatMessage, MessageRow> rows = new IdentityHashMap<>();
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
        scroll.getVerticalScrollBar().addAdjustmentListener(event -> updateJumpButton());
        add(scroll, BorderLayout.CENTER);
        jumpToBottom.setBorderPainted(false);
        jumpToBottom.setContentAreaFilled(false);
        jumpToBottom.setFocusPainted(false);
        jumpToBottom.setFont(JBUI.Fonts.smallFont());
        jumpToBottom.setForeground(ChatStyles.link());
        jumpToBottom.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        jumpToBottom.addActionListener(event -> scrollToBottom());
        bottomBar.setOpaque(false);
        bottomBar.setVisible(false);
        bottomBar.add(jumpToBottom);
        add(bottomBar, BorderLayout.SOUTH);
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                list.revalidate();
                updateJumpButton();
            }
        });
    }

    void add(@NotNull ChatMessage message) {
        boolean stick = isStuckToBottom();
        MessageRow row = new MessageRow(message);
        list.add(row);
        rows.put(message, row);
        messages.add(message);
        list.revalidate();
        list.repaint();
        if (stick || rows.size() == 1) {
            scrollToBottom();
        }
        updateJumpButton();
    }

    /** Re-renders one card and its metadata, used for every stream delta and when a turn finishes. */
    void refresh(@NotNull ChatMessage message) {
        MessageRow row = rows.get(message);
        if (row == null) {
            return;
        }
        boolean stick = isStuckToBottom();
        row.card.refresh();
        row.meta.refresh();
        list.revalidate();
        if (stick) {
            scrollToBottom();
        }
        updateJumpButton();
    }

    /** Removes one turn, used when the latest answer is regenerated. */
    void remove(@NotNull ChatMessage message) {
        MessageRow row = rows.remove(message);
        messages.remove(message);
        if (row != null) {
            list.remove(row);
            list.revalidate();
            list.repaint();
            updateJumpButton();
        }
    }

    void setRetryAction(@NotNull ChatMessage message, @NotNull JButton action) {
        MessageRow row = rows.get(message);
        if (row != null) {
            row.meta.setRetryAction(action);
            list.revalidate();
        }
    }

    /** Only the newest answer keeps a regenerate button. */
    void clearRetryActions() {
        for (MessageRow row : rows.values()) {
            row.meta.setRetryAction(null);
        }
        list.revalidate();
    }

    void clear() {
        list.removeAll();
        rows.clear();
        messages.clear();
        list.revalidate();
        list.repaint();
        updateJumpButton();
    }

    int messageCount() {
        return rows.size();
    }

    @NotNull
    List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    private void updateJumpButton() {
        bottomBar.setVisible(!isStuckToBottom());
    }

    private boolean isStuckToBottom() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - STICK_THRESHOLD;
    }

    void scrollToBottom() {
        ApplicationManager.getApplication().invokeLater(() -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
            updateJumpButton();
        }, ModalityState.any());
    }

    private final class MessageRow extends JPanel {

        private final MessageCard card;
        private final MessageMeta meta;
        private final boolean user;
        private Dimension cardSize = new Dimension(220, 36);
        private int metaHeight;

        private MessageRow(ChatMessage message) {
            user = message.role() == ChatMessage.Role.USER;
            card = new MessageCard(message, renderer, insertCode);
            meta = new MessageMeta(message);
            setLayout(null);
            setOpaque(false);
            add(card);
            // Always present so it can appear when an answer finishes; refresh() controls its visibility.
            add(meta);
        }

        private int measure(int width) {
            cardSize = card.measure(Math.max(120, width));
            metaHeight = 0;
            if (meta.getParent() != null && meta.isVisible()) {
                meta.setSize(new Dimension(width, Short.MAX_VALUE));
                metaHeight = meta.getPreferredSize().height;
            }
            return cardSize.height + metaHeight;
        }

        @Override
        public void doLayout() {
            int x = user ? Math.max(0, getWidth() - cardSize.width) : 0;
            card.setBounds(x, 0, cardSize.width, cardSize.height);
            if (meta.getParent() != null && meta.isVisible()) {
                meta.setBounds(0, cardSize.height, getWidth(), metaHeight);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            int width = getWidth() > 0 ? getWidth() : 320;
            return new Dimension(width, measure(width));
        }
    }

    private static final class MessageList extends JPanel implements Scrollable {

        private MessageList() {
            setOpaque(false);
        }

        @Override
        public void doLayout() {
            Insets insets = getInsets();
            int width = widthForLayout();
            int y = insets.top;
            for (Component child : getComponents()) {
                if (!(child instanceof ChatTranscript.MessageRow row) || !row.isVisible()) {
                    continue;
                }
                int height = row.measure(width);
                row.setBounds(insets.left, y, width, height);
                y += height + ROW_GAP;
            }
        }

        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int width = widthForLayout();
            int height = insets.top + insets.bottom;
            for (Component child : getComponents()) {
                if (!(child instanceof ChatTranscript.MessageRow row) || !row.isVisible()) {
                    continue;
                }
                height += row.measure(width) + ROW_GAP;
            }
            return new Dimension(width + insets.left + insets.right, Math.max(1, height));
        }

        /** During a viewport resize the list width can be one pass behind; fall back to the viewport width. */
        private int widthForLayout() {
            Insets insets = getInsets();
            int width = getWidth() - insets.left - insets.right;
            if (width <= 0 && getParent() != null) {
                width = getParent().getWidth() - insets.left - insets.right;
            }
            return Math.max(120, width);
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
