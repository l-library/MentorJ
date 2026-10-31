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

/** 编辑器右键：按自然语言需求为选中的类 / 方法生成 JUnit 测试。 */
public final class GenerateUnitTestAction extends AnAction implements DumbAware {

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
        String title = MentorJBundle.message("test.title");
        EditorContext context = EditorContexts.from(editor);
        boolean noSelection = context.selectedCode() == null || context.selectedCode().isBlank();
        if (noSelection && (context.sourceCode() == null || context.sourceCode().isBlank())) {
            Messages.showInfoMessage(project, MentorJBundle.message("test.noCode"), title);
            return;
        }
        // 作业要求先让用户用自然语言描述要测试的场景，再结合选中的代码生成测试。
        String scenario = Messages.showMultilineInputDialog(project, MentorJBundle.message("test.prompt"),
                title, null, Messages.getQuestionIcon(), null);
        if (scenario == null) {
            return;
        }
        if (scenario.isBlank()) {
            Messages.showInfoMessage(project, MentorJBundle.message("test.noScenario"), title);
            return;
        }
        AgentRequests.showResult(project, title,
                new AgentRequest(AgentAction.GENERATE_UNIT_TEST, scenario.strip(), context, null));
    }
}
