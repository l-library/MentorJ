package edu.mentorj;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/** UI 文案统一从 messages/MentorJBundle.properties 读取。 */
public final class MentorJBundle extends DynamicBundle {

    public static final String BUNDLE = "messages.MentorJBundle";

    private static final MentorJBundle INSTANCE = new MentorJBundle();

    private MentorJBundle() {
        super(BUNDLE);
    }

    public static @NotNull String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object... params) {
        return INSTANCE.getMessage(key, params);
    }
}
