package edu.mentorj.api;

import org.jetbrains.annotations.Nullable;

/**
 * 界面组发给 agent 的一次请求。各操作对字段的要求见 API.md。
 *
 * @param action        要执行的智能操作
 * @param userInput     用户输入：问题、测试场景等，可为空
 * @param editorContext 编辑器上下文，可为空
 * @param stagedDiff    staged 改动（git diff 格式），仅 commit message 生成用，可为空
 */
public record AgentRequest(AgentAction action,
                           @Nullable String userInput,
                           @Nullable EditorContext editorContext,
                           @Nullable String stagedDiff) {
}
