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
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            return;
        }
        // TODO(界面组): 弹输入框收集问题，后台线程调用
        //  AgentService.respond(new AgentRequest(ASK, 问题, EditorContexts.from(editor), null))，
        //  然后展示回答与引用 / 通用知识声明
        Messages.showInfoMessage(e.getProject(),
                MentorJBundle.message("common.notImplemented"), MentorJBundle.message("common.title"));
    }
}
