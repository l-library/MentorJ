package edu.mentorj.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import edu.mentorj.MentorJBundle;
import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import org.jetbrains.annotations.NotNull;

/**
 * Generate Git commit message: analyze staged changes and write a Conventional Commits message.
 *
 * <p>Collecting the diff has to spawn a git process, which cannot be waited on from the EDT, so the changes
 * are read on a background thread before the request goes out. When nothing is staged or the project is not
 * a Git repository, the action says so plainly instead of handing an empty string to the agent.
 */
public final class GenerateCommitMessageAction extends AnAction implements DumbAware {

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(
                project, MentorJBundle.message("git.readingDiff"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                StagedDiff.Outcome outcome = StagedDiff.collect(project.getBasePath());
                if (indicator.isCanceled()) {
                    return;
                }
                ApplicationManager.getApplication().invokeLater(
                        () -> generate(project, outcome), ModalityState.any());
            }
        });
    }

    private static void generate(@NotNull Project project, @NotNull StagedDiff.Outcome outcome) {
        String title = MentorJBundle.message("git.title");
        if (outcome.failure() != null) {
            Messages.showErrorDialog(project, outcome.failure(), title);
            return;
        }
        String diff = outcome.diff();
        if (diff == null || diff.isBlank()) {
            Messages.showInfoMessage(project, MentorJBundle.message("git.stagedDiffEmpty"), title);
            return;
        }
        AgentRequests.showResult(project, title,
                new AgentRequest(AgentAction.GENERATE_COMMIT_MESSAGE, null, null, diff));
    }
}
