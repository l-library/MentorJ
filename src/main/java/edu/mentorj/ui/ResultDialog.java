package edu.mentorj.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import edu.mentorj.MentorJBundle;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.AgentResponse;
import org.jetbrains.annotations.NotNull;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.List;

/**
 * 展示 agent 生成结果的对话框：回答、JUnit 测试类、练习题或提交消息。
 *
 * <p>打开即开始请求，正文按增量刷新；关闭或点停止都会取消后台任务，已收到的部分保留。
 */
final class ResultDialog extends DialogWrapper implements Disposable {

    private final Project project;
    private final AgentRequest request;
    private final ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
    private final ChatTranscript transcript;
    private final Action stopAction = new AbstractAction(MentorJBundle.message("chat.stop")) {
        @Override
        public void actionPerformed(ActionEvent event) {
            stop();
        }
    };

    private AgentRequests.Handle handle;
    private boolean disposed;

    ResultDialog(@NotNull Project project, @NotNull String title, @NotNull AgentRequest request) {
        super(project, true);
        this.project = project;
        this.request = request;
        this.transcript = new ChatTranscript(new MarkdownRenderer(new CodeHighlighter(project),
                MentorJBundle.message("chat.copyCode"), null), null);
        transcript.add(answer);
        setTitle(title);
        setOKButtonText(MentorJBundle.message("dialog.close"));
        stopAction.setEnabled(false);
        init();
    }

    @Override
    public void show() {
        start();
        super.show();
    }

    private void start() {
        long started = System.nanoTime();
        stopAction.setEnabled(true);
        handle = AgentRequests.submit(project, getTitle(), request, new AgentRequests.Callbacks() {
            @Override
            public void onText(String text) {
                if (disposed) {
                    return;
                }
                answer.text(text);
                transcript.refresh(answer);
            }

            @Override
            public void onToolCalls(List<String> toolNames) {
                if (disposed) {
                    return;
                }
                answer.text("");
                transcript.refresh(answer);
            }

            @Override
            public void onDone(AgentResponse response) {
                if (disposed) {
                    return;
                }
                if (AgentRequests.isError(response)) {
                    answer.failed(response.text());
                } else {
                    answer.done(response.text(), response.citations(), response.generalKnowledgeOnly());
                }
                answer.elapsed((System.nanoTime() - started) / 1_000_000);
                transcript.refresh(answer);
                stopAction.setEnabled(false);
            }

            @Override
            public void onCancelled() {
                if (disposed) {
                    return;
                }
                answer.cancelled();
                answer.elapsed((System.nanoTime() - started) / 1_000_000);
                transcript.refresh(answer);
                stopAction.setEnabled(false);
            }

            @Override
            public void onError(String message) {
                if (disposed) {
                    return;
                }
                answer.failed(message);
                answer.elapsed((System.nanoTime() - started) / 1_000_000);
                transcript.refresh(answer);
                stopAction.setEnabled(false);
            }
        });
    }

    private void stop() {
        stopAction.setEnabled(false);
        if (handle != null) {
            handle.cancel();
        }
    }

    @Override
    protected @NotNull Action[] createActions() {
        return new Action[]{stopAction, getOKAction()};
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(JBUI.scale(640), JBUI.scale(420)));
        panel.add(transcript, BorderLayout.CENTER);
        return panel;
    }

    @Override
    public void dispose() {
        disposed = true;
        if (handle != null) {
            handle.cancel();
        }
        super.dispose();
    }
}
