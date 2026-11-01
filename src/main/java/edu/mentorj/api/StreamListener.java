package edu.mentorj.api;

import java.util.List;

/**
 * Answer increment callback. A plain Java type, which keeps the {@code api} package free of platform APIs.
 *
 * <p>Callbacks run on the caller's background thread; the UI team switches back to the EDT itself when it needs
 * to update the UI. Concatenating the increments in order equals the final {@code AgentResponse.text()};
 * an implementation may also call back once with the whole text.
 */
public interface StreamListener {

    /**
     * An increment of the answer body.
     *
     * @param delta the newly added text fragment, non-empty
     */
    void onText(String delta);

    /**
     * This round ended in tool calls: the text increments received so far are not the final answer, so the UI
     * should discard them and show the tool status.
     *
     * @param toolNames names of the tools to run this round, in call order
     */
    default void onToolCalls(List<String> toolNames) {
    }

    /** Cancel polling; when it returns {@code true} the request should wind down as soon as possible. */
    default boolean isCancelled() {
        return false;
    }
}
