package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import edu.mentorj.MentorJBundle;
import edu.mentorj.MentorJProjectService;
import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.AgentResponse;
import edu.mentorj.api.AgentService;
import edu.mentorj.api.Citation;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/** 侧边栏 tool window：与助教对话的最小可用界面，完整交互由界面组继续实现。 */
public final class MentorJToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        toolWindow.setTitle(MentorJBundle.message("toolwindow.title"));

        JBTextField input = new JBTextField();
        JButton send = new JButton(MentorJBundle.message("toolwindow.send"));
        JBTextArea output = new JBTextArea();
        output.setEditable(false);
        output.setLineWrap(true);

        Runnable sendQuestion = () -> {
            String question = input.getText().strip();
            if (question.isEmpty()) {
                return;
            }
            AgentService agentService = MentorJProjectService.getInstance(project).agentService();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                AgentResponse response =
                        agentService.respond(new AgentRequest(AgentAction.ASK, question, null, null));
                ApplicationManager.getApplication().invokeLater(() -> output.setText(render(response)));
            });
        };
        send.addActionListener(e -> sendQuestion.run());
        input.addActionListener(e -> sendQuestion.run());

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.add(input, BorderLayout.CENTER);
        inputRow.add(send, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(8));
        panel.add(inputRow, BorderLayout.NORTH);
        panel.add(new JBScrollPane(output), BorderLayout.CENTER);

        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    private static String render(AgentResponse response) {
        StringBuilder text = new StringBuilder(response.text() == null ? "" : response.text());
        if (response.generalKnowledgeOnly()) {
            text.append("\n\n").append(MentorJBundle.message("toolwindow.generalKnowledgeNotice"));
        }
        for (Citation citation : response.citations()) {
            text.append('\n').append(MentorJBundle.message("toolwindow.citation", citation.document(), citation.pageOrScope()));
        }
        return text.toString();
    }
}
