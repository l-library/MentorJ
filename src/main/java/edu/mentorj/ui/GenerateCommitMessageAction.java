package edu.mentorj.ui;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.Messages;
import edu.mentorj.MentorJBundle;
import org.jetbrains.annotations.NotNull;

/** 生成 Git 提交消息：分析 staged 改动并按 Conventional Commits 生成提交消息。 */
public final class GenerateCommitMessageAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // TODO(界面组): 采集 staged 改动（git diff），后台线程调用
        //  AgentService.respond(new AgentRequest(GENERATE_COMMIT_MESSAGE, null, null, diff))，
        //  然后展示生成的提交消息
        Messages.showInfoMessage(e.getProject(),
                MentorJBundle.message("common.notImplemented"), MentorJBundle.message("common.title"));
    }
}
