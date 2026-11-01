package edu.mentorj.api;

import org.jetbrains.annotations.Nullable;

/**
 * One request the UI team sends to the agent. API.md lists which fields each action requires.
 *
 * @param action        the intelligent action to perform
 * @param userInput     user input such as the question or test scenario; may be empty
 * @param editorContext editor context; may be empty
 * @param stagedDiff    staged changes (git diff format), used only for commit message generation; may be empty
 */
public record AgentRequest(AgentAction action,
                           @Nullable String userInput,
                           @Nullable EditorContext editorContext,
                           @Nullable String stagedDiff) {
}
