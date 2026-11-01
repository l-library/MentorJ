package edu.mentorj.api;

import org.jetbrains.annotations.Nullable;

/**
 * Editor context, collected by the UI team and passed to the agent with the request.
 *
 * @param filePath     file path, {@code null} when not provided
 * @param sourceCode   source of the current file, {@code null} when not provided
 * @param selectedCode code selected in the editor, {@code null} when nothing is selected
 */
public record EditorContext(@Nullable String filePath,
                            @Nullable String sourceCode,
                            @Nullable String selectedCode) {
}
