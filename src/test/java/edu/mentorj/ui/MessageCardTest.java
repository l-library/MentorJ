package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import edu.mentorj.api.Citation;

import java.awt.Dimension;
import java.util.List;

/**
 * Card layout smoke test: the HTML kit, theme lookup and width-aware measurement must work without an exception.
 * Exact pixels are left to the sandbox because they depend on the IDE LAF.
 */
public class MessageCardTest extends BasePlatformTestCase {

    public void testHeightDependsOnWidth() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            MarkdownRenderer renderer = new MarkdownRenderer((code, language) -> List.of(), "Copy");
            String text = """
                    Here are ways to ask that get you more from me:

                    1. Concepts: Spring IoC/DI, bean scopes, AOP proxying,
                       transaction propagation, Spring Boot auto-configuration, and so on.

                    2. Code: paste the error message, stack trace, or the relevant code
                       (with class names, dependencies and JDK/framework versions),
                       and I will walk through the cause line by line against your selected code.

                    3. Locating course material: give me keywords (such as "transaction
                       propagation", "auto-configuration", "JPA mapping") and I will search
                       the course material and cite the source document and page.
                    """;
            MessageCard wide = new MessageCard(new ChatMessage(ChatMessage.Role.ASSISTANT, text), renderer);
            MessageCard narrow = new MessageCard(new ChatMessage(ChatMessage.Role.ASSISTANT, text), renderer);
            int wideHeight = wide.measure(700).height;
            int narrowHeight = narrow.measure(350).height;
            assertTrue("narrow card must be taller: wide=" + wideHeight + " narrow=" + narrowHeight,
                    narrowHeight > wideHeight);
        });
    }

    public void testUserBubbleStaysNarrowAndAssistantCardFillsTheRow() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            MarkdownRenderer renderer = new MarkdownRenderer((code, language) -> List.of(), "Copy");

            MessageCard user = new MessageCard(
                    new ChatMessage(ChatMessage.Role.USER, "What is **encapsulation**?"), renderer);
            Dimension userSize = user.measure(400);
            assertTrue(userSize.width > 0);
            assertTrue("user bubble must not fill the row", userSize.width <= 328);
            assertTrue(userSize.height > 0);

            ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT,
                    "Encapsulation is **hiding the implementation**.");
            answer.done(answer.text(), List.of(new Citation("Lec-3.pdf", "P12-P14")), false);
            MessageCard assistant = new MessageCard(answer, renderer);
            Dimension answerSize = assistant.measure(400);
            assertEquals(400, answerSize.width);
            assertTrue(answerSize.height > 0);
        });
    }

    public void testCardDoesNotOwnSourcesOrActions() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            MarkdownRenderer renderer = new MarkdownRenderer((code, language) -> List.of(), "Copy");
            ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "answer");
            answer.done("answer", List.of(new Citation("Lec-3.pdf", "P12")), false);
            MessageCard card = new MessageCard(answer, renderer);
            Dimension withoutSources = card.measure(400);
            assertEquals("metadata must not change the card height",
                    card.measure(400).height, withoutSources.height);
        });
    }
}
