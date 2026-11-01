package edu.mentorj.ui;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import edu.mentorj.api.EditorContext;
import org.jetbrains.annotations.NotNull;

/** Collects editor context when the UI team triggers an intelligent action. */
final class EditorContexts {

    private EditorContexts() {
    }

    static EditorContext from(@NotNull Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return new EditorContext(
                file != null ? file.getPath() : null,
                editor.getDocument().getText(),
                editor.getSelectionModel().getSelectedText());
    }

    /** Generating a JUnit test only makes sense for Java files, which is what decides whether the menu item appears. */
    static boolean isJavaFile(@NotNull Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file != null && "java".equalsIgnoreCase(file.getExtension());
    }
}
