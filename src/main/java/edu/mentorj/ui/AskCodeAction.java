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

/** 编辑器右键：就选中代码向助教提问。 */
public final class AskCodeAction extends AnAction implements DumbAware {

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabledAndVisible(editor != null && editor.getSelectionModel().hasSelection());
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
        String title = MentorJBundle.message("ask.title");
        EditorContext context = EditorContexts.from(editor);
        if (context.selectedCode() == null || context.selectedCode().isBlank()) {
            Messages.showInfoMessage(project, MentorJBundle.message("ask.noSelection"), title);
            return;
        }
        String question = Messages.showInputDialog(project, MentorJBundle.message("ask.prompt"),
                title, Messages.getQuestionIcon());
        if (question == null || question.isBlank()) {
            return;
        }
        AgentRequests.showResult(project, title,
                new AgentRequest(AgentAction.ASK, question.strip(), context, null));
    }
}
