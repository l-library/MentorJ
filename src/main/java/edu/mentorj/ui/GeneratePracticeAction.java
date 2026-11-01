package edu.mentorj.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import edu.mentorj.MentorJBundle;
import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.EditorContext;
import org.jetbrains.annotations.NotNull;

/**
 * Editor context menu: generate practice exercises around the selected code (the F1 creative intelligent action).
 *
 * <p>What sets it apart from the other two intelligent actions is that the input is optional: leave it blank
 * and the teaching assistant picks the exercise types and difficulty itself, so a demo shows a result in one
 * click while a teacher can still insist on something like "only bug-finding exercises".
 */
public final class GeneratePracticeAction extends AnAction implements DumbAware {

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabledAndVisible(editor != null && EditorContexts.isJavaFile(editor));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        String title = MentorJBundle.message("practice.title");
        EditorContext context = EditorContexts.from(editor);
        boolean noCode = (context.selectedCode() == null || context.selectedCode().isBlank())
                && (context.sourceCode() == null || context.sourceCode().isBlank());
        if (noCode) {
            Messages.showInfoMessage(project, MentorJBundle.message("practice.noCode"), title);
            return;
        }
        // The input may stay blank: pass null then, so the agent retrieves by the code and designs the exercises.
        String focus = Messages.showMultilineInputDialog(project, MentorJBundle.message("practice.prompt"),
                title, null, Messages.getQuestionIcon(), null);
        if (focus == null) {
            return;
        }
        String requirement = focus.isBlank() ? null : focus.strip();
        AgentRequests.showResult(project, title,
                new AgentRequest(AgentAction.CREATIVE, requirement, context, null));
    }
}
