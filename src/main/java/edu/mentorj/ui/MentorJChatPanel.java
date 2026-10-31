package edu.mentorj.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import edu.mentorj.MentorJBundle;
import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * 侧边栏主界面：课程材料入口 + 对话记录 + 提问输入。
 *
 * <p>对话记录只是本机的展示历史：agent 每次请求独立处理，不保留跨问题的上下文，所以界面不暗示
 * 可以“追问”。请求在后台线程执行，界面只在 EDT 更新。
 */
final class MentorJChatPanel extends JPanel implements Disposable {

    private final Project project;
    private final List<String> transcript = new ArrayList<>();
    private final JBTextArea transcriptArea = new JBTextArea();
    private final JBScrollPane scroll = new JBScrollPane(transcriptArea);
    private final JBTextField input = new JBTextField();
    private final JButton sendButton = new JButton(MentorJBundle.message("toolwindow.send"));

    private boolean busy;
    private boolean disposed;
    private int pending = -1;

    MentorJChatPanel(@NotNull Project project) {
        super(new BorderLayout(0, 8));
        this.project = project;
        setBorder(JBUI.Borders.empty(8));

        transcriptArea.setEditable(false);
        transcriptArea.setLineWrap(true);
        transcriptArea.setWrapStyleWord(true);
        transcriptArea.setBorder(JBUI.Borders.empty(4));

        input.addActionListener(e -> send());
        sendButton.addActionListener(e -> send());

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);

        add(new CourseMaterialPanel(project, this::appendSystem), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(inputRow, BorderLayout.SOUTH);

        appendSystem(MentorJBundle.message("chat.emptyState"));
    }

    private void send() {
        if (busy || disposed) {
            return;
        }
        String question = input.getText().strip();
        if (question.isEmpty()) {
            return;
        }
        input.setText("");
        transcript.add(MentorJBundle.message("chat.question", question));
        pending = transcript.size();
        transcript.add(answered(MentorJBundle.message("chat.thinking")));
        render();
        setBusy(true);
        AgentRequests.submit(project, MentorJBundle.message("chat.taskTitle"),
                new AgentRequest(AgentAction.ASK, question, null, null),
                this::completePending,
                this::finishTurn);
    }

    private void completePending(String text) {
        if (disposed || pending < 0 || pending >= transcript.size()) {
            return;
        }
        transcript.set(pending, answered(text == null ? MentorJBundle.message("chat.cancelled") : text));
        pending = -1;
        render();
    }

    private void finishTurn() {
        setBusy(false);
        if (!disposed) {
            input.requestFocusInWindow();
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setEnabled(!value);
    }

    private void appendSystem(String text) {
        if (disposed) {
            return;
        }
        transcript.add(MentorJBundle.message("chat.system", text));
        render();
    }

    private void render() {
        transcriptArea.setText(String.join("\n\n", transcript));
        transcriptArea.setCaretPosition(transcriptArea.getDocument().getLength());
        // 新内容要等这一轮布局完成才知道滚到底部的准确位置，所以延后一次再滚。
        ApplicationManager.getApplication().invokeLater(() -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        }, ModalityState.any());
    }

    private static String answered(String body) {
        return MentorJBundle.message("chat.answer", body);
    }

    @Override
    public void dispose() {
        disposed = true;
    }
}
