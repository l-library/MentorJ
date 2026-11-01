package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.scale.JBUIScale;
import edu.mentorj.api.Citation;

import javax.swing.JEditorPane;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

/**
 * Regression test for the assistant answer being clipped while the sidebar is resized: rows must be measured at
 * their actual width, and the sources below the card must not take space from the answer.
 */
public class ChatTranscriptTest extends BasePlatformTestCase {

    public void testScrollbarAppearanceDoesNotClipCompletedAnswer() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            ChatTranscript transcript = new ChatTranscript(
                    new MarkdownRenderer((code, language) -> List.of(), "Copy"));
            transcript.setSize(520, 260);
            ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
            transcript.add(answer);
            layoutTwice(transcript);

            answer.text("partial");
            transcript.refresh(answer);
            layoutTwice(transcript);

            answer.done("""
                    Here are ways to ask that get you more from me:
                    1. Concepts: Spring IoC/DI, bean scopes, AOP proxying, transaction
                        propagation, how Spring Boot auto-configuration works.
                    2. Code: paste the error message, stack trace, or the relevant code (with class
                        names, dependencies, and JDK/framework versions), and I will walk through the
                        cause line by line against your selected code.
                    3. Locating course material: give me keywords (such as "transaction propagation",
                        "auto-configuration", "JPA mapping") and I will search the course material and
                        cite the source document and page.
                    4. Lab and assignment approach: I can outline an approach and a minimal runnable
                        example, but I will not claim to have run your tests or edited your files,
                        because none of that happened in this conversation.
                    5. One more paragraph, to verify the card height is recomputed once the scrollbar
                        appears.
                    6. And one more line, so that the body is taller than the visible area.
                    """, List.of(new Citation("Lec-5-Building-AI-Powered-Java-Applications.pdf", "p.37")), false);
            transcript.refresh(answer);
            layoutTwice(transcript);

            JEditorPane body = find(transcript, JEditorPane.class);
            assertNotNull(body);
            assertTrue("body bounds " + body.getBounds() + ", body pref " + body.getPreferredSize(),
                    body.getHeight() >= body.getPreferredSize().height);
            MessageMeta meta = find(transcript, MessageMeta.class);
            assertNotNull(meta);
            assertTrue(meta.isVisible());
            assertTrue("sources must have their own height", meta.getHeight() > 0);

            // Resizing the sidebar used to leave one layout pass with the height measured at the old width.
            transcript.setSize(380, 260);
            layoutTwice(transcript);
            assertTrue("body bounds after resize " + body.getBounds() + ", pref " + body.getPreferredSize(),
                    body.getHeight() >= body.getPreferredSize().height);
        });
    }

    /**
     * At 150-200% UI scale the user bubble used to lose its last line: the card's padding was scaled twice, so the
     * body was laid out narrower than the width the card measured it at, and the extra wrapped line fell outside
     * the card height.
     */
    public void testUserBubbleKeepsItsLastLineAtHighUiScale() {
        JBUIScale.setUserScaleFactorForTest(2f);
        try {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                ChatTranscript transcript = new ChatTranscript(
                        new MarkdownRenderer((code, language) -> List.of(), "Copy"));
                transcript.setSize(520, 260);
                ChatMessage question = new ChatMessage(ChatMessage.Role.USER, "How to create a subclass?");
                transcript.add(question);
                layoutTwice(transcript);

                JEditorPane body = find(transcript, JEditorPane.class);
                assertNotNull(body);
                assertTrue("body bounds " + body.getBounds() + ", body pref " + body.getPreferredSize(),
                        body.getHeight() >= body.getPreferredSize().height);
            });
        } finally {
            JBUIScale.setUserScaleFactorForTest(1f);
        }
    }

    private static void layoutTwice(Container container) {
        layout(container);
        layout(container);
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layout(nested);
            }
        }
    }

    private static <T> T find(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            if (child instanceof Container nested) {
                T found = find(nested, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
