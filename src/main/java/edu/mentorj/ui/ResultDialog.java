package edu.mentorj.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import edu.mentorj.MentorJBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.datatransfer.StringSelection;

/** 展示 agent 生成结果的只读对话框：回答、JUnit 测试类或提交消息，均带复制按钮。 */
final class ResultDialog extends DialogWrapper {

    private final String text;
    private final JButton copyButton = new JButton(MentorJBundle.message("dialog.copy"));

    ResultDialog(@Nullable Project project, @NotNull String title, @NotNull String text) {
        super(project, true);
        this.text = text;
        setTitle(title);
        setOKButtonText(MentorJBundle.message("dialog.close"));
        init();
    }

    @Override
    protected @NotNull Action[] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JBTextArea area = new JBTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(JBUI.Borders.empty(4));

        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setPreferredSize(new Dimension(760, 420));

        copyButton.addActionListener(e -> {
            CopyPasteManager.getInstance().setContents(new StringSelection(text));
            copyButton.setText(MentorJBundle.message("dialog.copied"));
        });
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(copyButton, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(4));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }
}
