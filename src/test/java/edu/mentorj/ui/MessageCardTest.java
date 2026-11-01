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
            MarkdownRenderer renderer = new MarkdownRenderer((code, language) -> List.of(), "复制");
            String text = """
                    你可以这样提问，我能帮你更多，例如：

                    1. 概念类：如 Spring 的 IoC/DI、Bean 作用域、AOP 代理方式、
                       事务传播行为、Spring Boot 自动配置原理等。

                    2. 代码类：把报错信息、堆栈或相关代码贴出来（说明类名、依赖、
                       JDK/框架版本），我按你选中的代码逐行分析原因。

                    3. 课程材料定位：告诉我关键词（如“事务传播”“自动配置”
                       “JPA 映射”），我去课程材料里检索并标注来源文档与页码。
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
            MarkdownRenderer renderer = new MarkdownRenderer((code, language) -> List.of(), "复制");

            MessageCard user = new MessageCard(
                    new ChatMessage(ChatMessage.Role.USER, "什么是**封装**？"), renderer);
            Dimension userSize = user.measure(400);
            assertTrue(userSize.width > 0);
            assertTrue("user bubble must not fill the row", userSize.width <= 328);
            assertTrue(userSize.height > 0);

            ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "封装是**隐藏实现**。");
            answer.done(answer.text(), List.of(new Citation("Lec-3.pdf", "P12-P14")), false);
            MessageCard assistant = new MessageCard(answer, renderer);
            Dimension answerSize = assistant.measure(400);
            assertEquals(400, answerSize.width);
            assertTrue(answerSize.height > 0);
        });
    }

    public void testCardDoesNotOwnSourcesOrActions() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            MarkdownRenderer renderer = new MarkdownRenderer((code, language) -> List.of(), "复制");
            ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "answer");
            answer.done("answer", List.of(new Citation("Lec-3.pdf", "P12")), false);
            MessageCard card = new MessageCard(answer, renderer);
            Dimension withoutSources = card.measure(400);
            assertEquals("metadata must not change the card height",
                    card.measure(400).height, withoutSources.height);
        });
    }
}
