package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import edu.mentorj.MentorJBundle;
import edu.mentorj.MentorJProjectService;
import edu.mentorj.api.DocumentLibrary;
import edu.mentorj.api.ImportResult;
import edu.mentorj.api.Statistics;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 课程材料入口：导入 PDF 建立索引、清空索引、显示索引现状。
 *
 * <p>{@link DocumentLibrary} 的导入、清空和统计都是阻塞调用，全部放后台线程；
 * {@link edu.mentorj.api.ProgressListener} 在导入线程被回调，这里自行切回 EDT。
 * 导入是全量重建，因此每次导入后都要刷新统计。
 */
final class CourseMaterialPanel extends JPanel {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Project project;
    private final Consumer<String> report;
    private final JBLabel status = new JBLabel();
    private final JButton importButton = new JButton(MentorJBundle.message("material.import"));
    private final JButton clearButton = new JButton(MentorJBundle.message("material.clear"));

    CourseMaterialPanel(@NotNull Project project, @NotNull Consumer<String> report) {
        super(new BorderLayout(8, 4));
        this.project = project;
        this.report = report;
        setBorder(JBUI.Borders.empty(0, 0, 4, 0));

        importButton.addActionListener(e -> importMaterials());
        clearButton.addActionListener(e -> clearIndex());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(importButton);
        buttons.add(clearButton);

        status.setText(MentorJBundle.message("material.unknown"));

        add(buttons, BorderLayout.NORTH);
        add(status, BorderLayout.CENTER);

        refresh();
    }

    private void importMaterials() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle(MentorJBundle.message("material.chooseTitle"))
                .withFileFilter(file -> !file.isDirectory() && "pdf".equalsIgnoreCase(file.getExtension()));
        VirtualFile[] chosen = FileChooser.chooseFiles(descriptor, project, null);
        if (chosen.length == 0) {
            return;
        }
        List<Path> documents = new ArrayList<>();
        for (VirtualFile file : chosen) {
            documents.add(Path.of(file.getPath()));
        }
        status.setText(MentorJBundle.message("material.importing", documents.size()));
        ProgressManager.getInstance().run(new Task.Backgroundable(
                project, MentorJBundle.message("material.importTask"), true) {
            private volatile ImportResult result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                result = library().importDocuments(documents, (done, total, name) ->
                        ApplicationManager.getApplication().invokeLater(() -> status.setText(
                                MentorJBundle.message("material.progress", done, total, name)),
                                ModalityState.any()));
            }

            @Override
            public void onFinished() {
                ImportResult imported = result;
                if (imported != null) {
                    report.accept(summary(imported));
                }
                refresh();
            }
        });
    }

    private void clearIndex() {
        String title = MentorJBundle.message("material.clearTitle");
        if (Messages.showYesNoDialog(project, MentorJBundle.message("material.clearConfirm"), title,
                Messages.getWarningIcon()) != Messages.YES) {
            return;
        }
        ProgressManager.getInstance().run(new Task.Backgroundable(
                project, MentorJBundle.message("material.clearTask"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                library().clear();
            }

            @Override
            public void onFinished() {
                report.accept(MentorJBundle.message("material.clearDone"));
                refresh();
            }
        });
    }

    private void refresh() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Statistics statistics;
            try {
                statistics = library().statistics();
            } catch (RuntimeException e) {
                ApplicationManager.getApplication().invokeLater(
                        () -> status.setText(AgentRequests.unexpected(e)), ModalityState.any());
                return;
            }
            Statistics loaded = statistics;
            ApplicationManager.getApplication().invokeLater(() -> show(loaded), ModalityState.any());
        });
    }

    private void show(@NotNull Statistics statistics) {
        if (statistics.indexedAt() == null) {
            status.setText(MentorJBundle.message("material.none"));
            return;
        }
        status.setText(MentorJBundle.message("material.status", statistics.documents().size(),
                statistics.chunks(), TIME.format(statistics.indexedAt())));
    }

    private DocumentLibrary library() {
        return MentorJProjectService.getInstance(project).documentLibrary();
    }

    private static String summary(@NotNull ImportResult result) {
        StringBuilder text = new StringBuilder(MentorJBundle.message(
                "material.importDone", result.documents().size(), result.chunks()));
        if (!result.failures().isEmpty()) {
            text.append('\n').append(MentorJBundle.message("material.importFailures", result.failures().size()));
            for (String failure : result.failures()) {
                text.append("\n  - ").append(failure);
            }
        }
        return text.toString();
    }
}
