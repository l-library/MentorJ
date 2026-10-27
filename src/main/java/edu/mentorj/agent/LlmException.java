package edu.mentorj.agent;

import java.text.MessageFormat;
import java.util.ResourceBundle;

/** Expected configuration, transport, or provider failure. Never includes raw provider responses or secrets. */
final class LlmException extends RuntimeException {

    LlmException(String key, Object... arguments) {
        // Use the standard bundle API to keep the agent independent of the IntelliJ runtime.
        super(MessageFormat.format(ResourceBundle.getBundle("messages.MentorJBundle").getString(key), arguments));
    }
}
