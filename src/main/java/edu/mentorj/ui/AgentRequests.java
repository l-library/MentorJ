package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import edu.mentorj.MentorJBundle;
import edu.mentorj.MentorJProjectService;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.AgentResponse;
import edu.mentorj.api.Citation;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * 界面组调用 agent 的唯一入口：统一后台执行、取消与结果文案。
 *
 * <p>{@link edu.mentorj.api.AgentService#respond} 同步阻塞且可能耗时数十秒，一律放进
 * {@link Task.Backgroundable}；任务可取消时平台会中断后台线程，agent 侧据此提前收尾。
 */
final class AgentRequests {

    private AgentRequests() {
    }

    /**
     * 后台执行一次请求，结束后在 EDT 回调。
     *
     * @param onDone 回答的可展示文案；{@code null} 表示本次请求被取消
     * @param onEnd  请求结束后的收尾动作，总是紧接着 {@code onDone} 在 EDT 执行
     */
    static void submit(@NotNull Project project,
                       @NotNull String title,
                       @NotNull AgentRequest request,
                       @NotNull Consumer<String> onDone,
                       @NotNull Runnable onEnd) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                String rendered;
                try {
                    AgentResponse response =
                            MentorJProjectService.getInstance(project).agentService().respond(request);
                    rendered = indicator.isCanceled() ? null : format(response);
                } catch (RuntimeException e) {
                    rendered = unexpected(e);
                }
                String text = rendered;
                // 用 invokeLater 而不是依赖 onSuccess / onFinished 的调用时机：取消和异常都能落到同一处。
                ApplicationManager.getApplication().invokeLater(() -> {
                    onDone.accept(text);
                    onEnd.run();
                }, ModalityState.any());
            }
        });
    }

    /** 后台执行一次请求，并把结果显示在只读对话框里；取消时不打扰用户。 */
    static void showResult(@NotNull Project project, @NotNull String title, @NotNull AgentRequest request) {
        submit(project, title, request, text -> {
            if (text != null) {
                new ResultDialog(project, title, text).show();
            }
        }, () -> {
        });
    }

    /** 把回答拼成可直接展示的文本：正文 + 引用来源 + 通用知识声明。 */
    static @NotNull String format(@NotNull AgentResponse response) {
        StringBuilder text = new StringBuilder();
        if (response.text() != null) {
            text.append(response.text().stripTrailing());
        }
        if (!response.citations().isEmpty()) {
            text.append("\n\n").append(MentorJBundle.message("response.citationsTitle"));
            for (Citation citation : response.citations()) {
                text.append('\n').append(MentorJBundle.message(
                        "response.citationItem", citation.document(), citation.pageOrScope()));
            }
        }
        if (response.generalKnowledgeOnly()) {
            text.append("\n\n").append(MentorJBundle.message("toolwindow.generalKnowledgeNotice"));
        }
        return text.toString();
    }

    /** 把未预期的异常转成用户能读的一句话，不暴露堆栈。 */
    static @NotNull String unexpected(@NotNull Throwable error) {
        String detail = error.getMessage();
        return MentorJBundle.message("error.unexpected",
                detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail);
    }
}
