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
 * The UI team's only entry point for calling the agent: it centralizes background execution, streamed increment
 * merging, cancellation, and result text.
 *
 * <p>{@link edu.mentorj.api.AgentService#respond} blocks synchronously, so every call goes into a
 * {@link Task.Backgroundable}. Increments arrive on the background thread and are merged here into one EDT
 * update every 60ms, so per-character repainting cannot overwhelm the UI.
 * All callbacks run on the EDT.
 */
final class AgentRequests {

    /** Lifecycle callbacks of one request, all called on the EDT. */
    interface Callbacks {
        /** The full text received so far (not an increment); an empty string the first time. */
        void onText(String text);

        /** This round ended in tool calls, so the text so far is not the final answer. */
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
     * Run one request on a background thread.
     *
     * @return a cancellable handle; cancelling ends with {@link Callbacks#onCancelled()}
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

    /** Open the result dialog and start the request immediately; the body streams into the dialog. */
    static void showResult(@NotNull Project project, @NotNull String title, @NotNull AgentRequest request) {
        new ResultDialog(project, title, request).show();
    }

    /**
     * An error answer from the agent carries neither citations nor the general knowledge flag, while a normal
     * answer always has one of the two (see the global rules in API.md). The UI relies on that to render
     * configuration and network errors as error cards instead of ordinary answers.
     */
    static boolean isError(@NotNull AgentResponse response) {
        return response.citations().isEmpty() && !response.generalKnowledgeOnly();
    }

    /** Turn an unexpected exception into one readable sentence for the user without exposing the stack trace. */
    static @NotNull String unexpected(@NotNull Throwable error) {
        String detail = error.getMessage();
        return MentorJBundle.message("error.unexpected",
                detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail);
    }

    private static void invokeLater(Runnable action) {
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any());
    }

    /** The background thread only appends and the EDT polls a snapshot on a timer; a repeat does not repaint. */
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
