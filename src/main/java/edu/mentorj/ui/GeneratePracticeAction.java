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
 * 编辑器右键：围绕选中的代码生成课后练习题（F1 的创意智能操作）。
 *
 * <p>与其他两个智能操作的区别是输入可选：留空时由助教自己决定题型与难度，
 * 这样演示时能一键看到效果，也允许教师指定“只出改错题”这类要求。
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
        // 输入可留空：留空时传 null，让 agent 用代码本身检索并自行设计题目。
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
