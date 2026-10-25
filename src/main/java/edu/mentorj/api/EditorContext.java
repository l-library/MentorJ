package edu.mentorj.api;

import org.jetbrains.annotations.Nullable;

/**
 * 编辑器上下文，由界面组采集后随请求传给 agent。
 *
 * @param filePath     文件路径，未提供时为 {@code null}
 * @param sourceCode   当前文件源码，未提供时为 {@code null}
 * @param selectedCode 编辑器中选中的代码段，未选中时为 {@code null}
 */
public record EditorContext(@Nullable String filePath,
                            @Nullable String sourceCode,
                            @Nullable String selectedCode) {
}
