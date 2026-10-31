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
import edu.mentorj.api.StreamListener;
import org.jetbrains.annotations.NotNull;

import javax.swing.Timer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 界面组调用 agent 的唯一入口：统一后台执行、流式增量合并、取消与结果文案。
 *
 * <p>{@link edu.mentorj.api.AgentService#respond} 同步阻塞，一律放进 {@link Task.Backgroundable}。
 * 增量在后台线程到达，这里合并成每 60ms 一次 EDT 更新，避免逐字刷新压垮界面。
 * 所有回调都在 EDT 上执行。
 */
final class AgentRequests {

    /** 一次请求的生命周期回调，全部在 EDT 上调用。 */
    interface Callbacks {
        /** 当前已收到的完整正文（不是增量），首次为空串。 */
        void onText(String text);

        /** 本轮以工具调用结束，此前的正文不是最终回答。 */
        default void onToolCalls(List<String> toolNames) {
        }

        void onDone(AgentResponse response);

        void onCancelled();

        void onError(String message);
    }

    @FunctionalInterface
    interface Handle {
        void cancel();
    }

    private AgentRequests() {
    }

    /**
     * 后台执行一次请求。
     *
     * @return 可取消句柄；取消后以 {@link Callbacks#onCancelled()} 收尾
     */
    static @NotNull Handle submit(@NotNull Project project,
                                  @NotNull String title,
                                  @NotNull AgentRequest request,
                                  @NotNull Callbacks callbacks) {
        DeltaPump pump = new DeltaPump(callbacks);
        Timer flush = new Timer(60, event -> pump.flush());
        flush.setCoalesce(true);
        flush.start();
        AtomicReference<ProgressIndicator> running = new AtomicReference<>();
        AtomicBoolean cancelRequested = new AtomicBoolean();
        Task.Backgroundable task = new Task.Backgroundable(project, title, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                running.set(indicator);
                if (cancelRequested.get()) {
                    indicator.cancel();
                }
                AgentResponse response;
                try {
                    response = MentorJProjectService.getInstance(project).agentService().respond(
                            request, new StreamListener() {
                                @Override
                                public void onText(String delta) {
                                    pump.append(delta);
                                }

                                @Override
                                public void onToolCalls(List<String> toolNames) {
                                    pump.reset();
                                    invokeLater(() -> callbacks.onToolCalls(toolNames));
                                }

                                @Override
                                public boolean isCancelled() {
                                    return indicator.isCanceled();
                                }
                            });
                } catch (RuntimeException e) {
                    String message = unexpected(e);
                    invokeLater(() -> {
                        flush.stop();
                        callbacks.onError(message);
                    });
                    return;
                }
                boolean cancelled = indicator.isCanceled();
                invokeLater(() -> {
                    flush.stop();
                    if (cancelled) {
                        callbacks.onCancelled();
                    } else {
                        pump.flush();
                        callbacks.onDone(response);
                    }
                });
            }
        };
        ProgressManager.getInstance().run(task);
        return () -> {
            cancelRequested.set(true);
            ProgressIndicator indicator = running.get();
            if (indicator != null) {
                indicator.cancel();
            }
        };
    }

    /** 打开结果对话框并立即开始请求，正文在对话框里流式刷新。 */
    static void showResult(@NotNull Project project, @NotNull String title, @NotNull AgentRequest request) {
        new ResultDialog(project, title, request).show();
    }

    /** 把未预期的异常转成用户能读的一句话，不暴露堆栈。 */
    static @NotNull String unexpected(@NotNull Throwable error) {
        String detail = error.getMessage();
        return MentorJBundle.message("error.unexpected",
                detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail);
    }

    private static void invokeLater(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any());
    }

    /** 后台线程只追加，EDT 定时读取快照；重复快照不触发重绘。 */
    private static final class DeltaPump {

        private final Callbacks callbacks;
        private final StringBuilder text = new StringBuilder();
        private String flushed = "";

        private DeltaPump(Callbacks callbacks) {
            this.callbacks = callbacks;
        }

        synchronized void append(String delta) {
            text.append(delta);
        }

        synchronized void reset() {
            text.setLength(0);
        }

        void flush() {
            String snapshot;
            synchronized (this) {
                snapshot = text.toString();
            }
            if (snapshot.equals(flushed)) {
                return;
            }
            flushed = snapshot;
            callbacks.onText(snapshot);
        }
    }
}
