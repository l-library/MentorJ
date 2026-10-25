package edu.mentorj.ui;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.Messages;
import edu.mentorj.MentorJBundle;
import org.jetbrains.annotations.NotNull;

/** 编辑器右键：按自然语言需求为选中的类 / 方法生成 JUnit 测试。 */
public final class GenerateUnitTestAction extends AnAction implements DumbAware {

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
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }
        // TODO(界面组): 弹文本输入框收集自然语言测试场景，后台线程调用
        //  AgentService.respond(new AgentRequest(GENERATE_UNIT_TEST, 场景, EditorContexts.from(editor), null))，
        //  然后展示生成的 JUnit 测试类
        Messages.showInfoMessage(e.getProject(),
                MentorJBundle.message("common.notImplemented"), MentorJBundle.message("common.title"));
    }
}
