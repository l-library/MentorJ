package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import edu.mentorj.api.Citation;

import java.awt.Dimension;
import java.util.List;

/**
 * Smoke test for the Swing side of a card: the HTML kit, the theme lookup and the width-aware layout must work
 * together without an exception. Exact pixels are left to the sandbox because they depend on the IDE LAF.
 */
public class MessageCardTest extends BasePlatformTestCase {

    public void testUserBubbleStaysNarrowAndAssistantCardFillsTheRow() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            MarkdownRenderer renderer = new MarkdownRenderer((code, language) -> List.of(), "复制");

            MessageCard user = new MessageCard(
                    new ChatMessage(ChatMessage.Role.USER, "什么是**封装**？"), renderer, () -> 400);
            Dimension userSize = user.getPreferredSize();
            assertTrue(userSize.width > 0);
            assertTrue("user bubble must not fill the row", userSize.width <= 328);
            assertTrue(userSize.height > 0);

            ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "封装是**隐藏实现**。");
            answer.done(answer.text(), List.of(new Citation("Lec-3.pdf", "P12-P14")), false);
            MessageCard assistant = new MessageCard(answer, renderer, () -> 400);
            Dimension answerSize = assistant.getPreferredSize();
            assertEquals(400, answerSize.width);
            assertTrue(answerSize.height > userSize.height);
        });
    }
}
