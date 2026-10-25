package edu.mentorj.ui;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import edu.mentorj.api.EditorContext;
import org.jetbrains.annotations.NotNull;

/** 界面组触发智能操作时采集编辑器上下文。 */
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
}
