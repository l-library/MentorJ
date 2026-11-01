package edu.mentorj.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import edu.mentorj.MentorJBundle;
import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.AgentResponse;
import edu.mentorj.api.EditorContext;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sidebar main view: course material entry point + message cards + input area.
 *
 * <p>Each question is handled independently (no context carries across requests); the view keeps only the local
 * display history and persists it per project. Requests run on a background thread, and {@link AgentRequests}
 * merges the increments back onto the EDT.
 */
final class MentorJChatPanel extends JPanel implements Disposable {

    private final Project project;
    private final MarkdownRenderer renderer;
    private final ChatTranscript transcript;
    private final ChatHistoryStore history;
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final JBTextArea input = new JBTextArea(1, 24);
    private final JBScrollPane inputScroll = new JBScrollPane(input);
    private final JButton sendButton = new JButton(MentorJBundle.message("toolwindow.send"));
    private final JButton stopButton = new JButton(MentorJBundle.message("chat.stop"));
    private final JBLabel status = new JBLabel();
    private final JPanel attachment = new JPanel(new BorderLayout(6, 0));
    private final JBLabel attachmentLabel = new JBLabel();
    private final Map<ChatMessage, AgentRequest> requests = new IdentityHashMap<>();

    private EditorContext attachedContext;
    private ChatMessage streaming;
    private AgentRequests.Handle handle;
    private boolean busy;
    private boolean disposed;

    MentorJChatPanel(@NotNull Project project) {
        super(new BorderLayout(0, 4));
        this.project = project;
        this.renderer = new MarkdownRenderer(new CodeHighlighter(project),
                MentorJBundle.message("chat.copyCode"), MentorJBundle.message("chat.insert"));
        this.transcript = new ChatTranscript(renderer, this::insertCode);
        this.history = new ChatHistoryStore(historyFile(project));

        add(createHeader(), BorderLayout.NORTH);
        center.add(createEmptyState(), "empty");
        center.add(transcript, "chat");
        add(center, BorderLayout.CENTER);
        add(createComposer(), BorderLayout.SOUTH);

        restoreHistory();
    }

    private @NotNull JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 2));
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(0, 8, 2, 8));
        header.add(new CourseMaterialPanel(project, this::appendSystem), BorderLayout.CENTER);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        toolbar.setOpaque(false);
        JButton clear = linkButton(MentorJBundle.message("chat.clear"), event -> clearConversation());
        clear.setFont(JBUI.Fonts.smallFont());
        toolbar.add(clear);
        header.add(toolbar, BorderLayout.SOUTH);
        return header;
    }

    private @NotNull JComponent createEmptyState() {
        JPanel wrapper = new JPanel(new GridBagLayout());
        wrapper.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.insets = JBUI.insets(4);
        constraints.anchor = GridBagConstraints.CENTER;

        JBLabel title = new JBLabel(MentorJBundle.message("chat.emptyTitle"));
        title.setFont(UIUtil.getLabelFont().deriveFont(Font.BOLD,
                UIUtil.getLabelFont().getSize2D() + 2));
        wrapper.add(title, constraints);

        constraints.gridy++;
        JBLabel hint = new JBLabel("<html><div align=\"center\">"
                + ChatStyles.escapeHtml(MentorJBundle.message("chat.emptyHint")) + "</div></html>");
        hint.setForeground(ChatStyles.mutedText());
        wrapper.add(hint, constraints);

        for (String suggestion : List.of("chat.suggestion.explain", "chat.suggestion.problem",
                "chat.suggestion.practice")) {
            constraints.gridy++;
            JButton button = linkButton(MentorJBundle.message(suggestion), event -> {
                input.setText(MentorJBundle.message(suggestion));
                resizeInput();
                input.requestFocusInWindow();
            });
            wrapper.add(button, constraints);
        }
        return wrapper;
    }

    private @NotNull JComponent createComposer() {
        JPanel composer = new JPanel(new BorderLayout(0, 4));
        composer.setOpaque(false);
        composer.setBorder(JBUI.Borders.empty(0, 8, 8, 8));

        attachment.setOpaque(false);
        attachment.setVisible(false);
        attachmentLabel.setFont(JBUI.Fonts.smallFont());
        attachmentLabel.setForeground(ChatStyles.mutedText());
        JButton detach = linkButton(MentorJBundle.message("chat.detach"), event -> detachSelection());
        detach.setFont(JBUI.Fonts.smallFont());
        attachment.add(attachmentLabel, BorderLayout.CENTER);
        attachment.add(detach, BorderLayout.EAST);

        status.setFont(JBUI.Fonts.smallFont());
        status.setForeground(ChatStyles.mutedText());
        status.setBorder(JBUI.Borders.empty(0, 2, 0, 2));

        JPanel meta = new JPanel();
        meta.setLayout(new BoxLayout(meta, BoxLayout.Y_AXIS));
        meta.setOpaque(false);
        meta.add(attachment);
        meta.add(status);

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setRows(1);
        input.setBorder(JBUI.Borders.empty(4, 6));
        input.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                resizeInput();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                resizeInput();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                resizeInput();
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "mentorj.send");
        input.getActionMap().put("mentorj.send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                send();
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                DefaultEditorKit.insertBreakAction);
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "mentorj.stop");
        input.getActionMap().put("mentorj.stop", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                stop();
            }
        });
        input.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                updateSelection();
            }
        });

        inputScroll.setBorder(JBUI.Borders.customLine(ChatStyles.cardBorder(), 1));
        inputScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        sendButton.addActionListener(event -> send());
        stopButton.addActionListener(event -> stop());
        stopButton.setEnabled(false);
        stopButton.setVisible(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(sendButton);
        buttons.add(stopButton);

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(inputScroll, BorderLayout.CENTER);
        row.add(buttons, BorderLayout.EAST);

        composer.add(meta, BorderLayout.NORTH);
        composer.add(row, BorderLayout.CENTER);
        return composer;
    }

    private void send() {
        if (busy || disposed) {
            return;
        }
        String question = input.getText().strip();
        if (question.isEmpty()) {
            return;
        }
        EditorContext context = attachedContext;
        input.setText("");
        resizeInput();
        centerCards.show(center, "chat");
        transcript.clearRetryActions();
        requests.clear();

        ChatMessage user = new ChatMessage(ChatMessage.Role.USER, question);
        ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
        transcript.add(user);
        transcript.add(answer);
        start(new AgentRequest(AgentAction.ASK, question, context, null), answer);
    }

    private void start(AgentRequest request, ChatMessage answer) {
        long started = System.nanoTime();
        streaming = answer;
        setBusy(true);
        status.setText(MentorJBundle.message("chat.thinking"));
        handle = AgentRequests.submit(project, MentorJBundle.message("chat.taskTitle"), request,
                new AgentRequests.Callbacks() {
                    @Override
                    public void onText(String text) {
                        if (disposed || answer != streaming) {
                            return;
                        }
                        answer.text(text);
                        transcript.refresh(answer);
                        if (!text.isEmpty()) {
                            status.setText("");
                        }
                    }

                    @Override
                    public void onToolCalls(List<String> toolNames) {
                        if (disposed || answer != streaming) {
                            return;
                        }
                        answer.text("");
                        transcript.refresh(answer);
                        status.setText(toolStatus(toolNames));
                    }

                    @Override
                    public void onDone(AgentResponse response) {
                        if (disposed || answer != streaming) {
                            return;
                        }
                        if (AgentRequests.isError(response)) {
                            answer.failed(response.text());
                        } else {
                            answer.done(response.text(), response.citations(), response.generalKnowledgeOnly());
                        }
                        answer.elapsed((System.nanoTime() - started) / 1_000_000);
                        transcript.refresh(answer);
                        finishTurn();
                        requests.put(answer, request);
                        transcript.setRetryAction(answer, retryButton(answer));
                        if (!AgentRequests.isError(response)) {
                            saveHistory();
                        }
                    }

                    @Override
                    public void onCancelled() {
                        if (disposed || answer != streaming) {
                            return;
                        }
                        answer.cancelled();
                        answer.elapsed((System.nanoTime() - started) / 1_000_000);
                        transcript.refresh(answer);
                        finishTurn();
                        requests.put(answer, request);
                        transcript.setRetryAction(answer, retryButton(answer));
                    }

                    @Override
                    public void onError(String message) {
                        if (disposed || answer != streaming) {
                            return;
                        }
                        answer.failed(message);
                        answer.elapsed((System.nanoTime() - started) / 1_000_000);
                        transcript.refresh(answer);
                        finishTurn();
                    }
                });
    }

    private @NotNull JButton retryButton(ChatMessage answer) {
        return linkButton(MentorJBundle.message("chat.regenerate"), event -> regenerate(answer));
    }

    private void regenerate(ChatMessage answer) {
        if (busy || disposed) {
            return;
        }
        AgentRequest request = requests.remove(answer);
        if (request == null) {
            return;
        }
        transcript.clearRetryActions();
        transcript.remove(answer);
        ChatMessage fresh = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
        transcript.add(fresh);
        start(request, fresh);
    }

    private void insertCode(String code) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            status.setText(MentorJBundle.message("chat.noEditor"));
            return;
        }
        WriteCommandAction.runWriteCommandAction(project,
                (Runnable) () -> editor.getDocument().insertString(editor.getCaretModel().getOffset(), code));
    }

    private void stop() {
        if (handle == null) {
            return;
        }
        status.setText(MentorJBundle.message("chat.status.cancelling"));
        stopButton.setEnabled(false);
        handle.cancel();
    }

    private void finishTurn() {
        streaming = null;
        handle = null;
        setBusy(false);
        status.setText("");
        if (!disposed) {
            input.requestFocusInWindow();
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setEnabled(!value);
        stopButton.setVisible(value);
        stopButton.setEnabled(value);
    }

    private void appendSystem(String text) {
        if (disposed) {
            return;
        }
        transcript.add(new ChatMessage(ChatMessage.Role.NOTICE, text));
        centerCards.show(center, "chat");
    }

    private void clearConversation() {
        if (Messages.showYesNoDialog(project, MentorJBundle.message("chat.clearConfirm"),
                MentorJBundle.message("chat.clearTitle"), Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        if (handle != null) {
            handle.cancel();
        }
        transcript.clear();
        requests.clear();
        history.save(List.of());
        centerCards.show(center, "empty");
    }

    private void restoreHistory() {
        List<ChatMessage> stored = history.load();
        for (ChatMessage message : stored) {
            transcript.add(message);
        }
        centerCards.show(center, stored.isEmpty() ? "empty" : "chat");
        if (!stored.isEmpty()) {
            transcript.scrollToBottom();
        }
    }

    private void saveHistory() {
        history.save(transcript.messages());
    }

    private void updateSelection() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || !editor.getSelectionModel().hasSelection()) {
            detachSelection();
            return;
        }
        EditorContext context = EditorContexts.from(editor);
        if (context.selectedCode() == null || context.selectedCode().isBlank()) {
            detachSelection();
            return;
        }
        // Only the selected fragment travels with the question; the full source would spend the context budget.
        attachedContext = new EditorContext(context.filePath(), null, context.selectedCode());
        attachmentLabel.setText(MentorJBundle.message("chat.attachSelection", describe(editor)));
        attachment.setVisible(true);
    }

    private @NotNull String describe(@NotNull Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        String name = file == null ? MentorJBundle.message("chat.unknownFile") : file.getName();
        int start = editor.getDocument().getLineNumber(editor.getSelectionModel().getSelectionStart()) + 1;
        int end = editor.getDocument().getLineNumber(editor.getSelectionModel().getSelectionEnd()) + 1;
        return end > start ? name + ":" + start + "-" + end : name + ":" + start;
    }

    private void detachSelection() {
        attachedContext = null;
        attachment.setVisible(false);
        attachmentLabel.setText("");
    }

    private void resizeInput() {
        int rows = Math.max(1, Math.min(5, input.getLineCount()));
        if (input.getRows() != rows) {
            input.setRows(rows);
            inputScroll.revalidate();
        }
    }

    private @NotNull String toolStatus(List<String> toolNames) {
        if (toolNames.contains("search_course_material")) {
            return MentorJBundle.message("chat.status.searching");
        }
        return MentorJBundle.message("chat.status.tool", String.join(", ", toolNames));
    }

    private static @NotNull JButton linkButton(@NotNull String text, @NotNull ActionListener listener) {
        JButton button = new JButton(text);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setForeground(ChatStyles.link());
        button.addActionListener(listener);
        return button;
    }

    private static Path historyFile(@NotNull Project project) {
        String base = project.getBasePath();
        return base == null ? null : Path.of(base, Project.DIRECTORY_STORE_FOLDER, "mentorj-chat.json");
    }

    @Override
    public void dispose() {
        disposed = true;
        if (handle != null) {
            handle.cancel();
        }
    }
}
