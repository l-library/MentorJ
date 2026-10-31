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

    /** 生成 JUnit 测试只对 Java 文件有意义，菜单项据此决定是否出现。 */
    static boolean isJavaFile(@NotNull Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file != null && "java".equalsIgnoreCase(file.getExtension());
    }
}
