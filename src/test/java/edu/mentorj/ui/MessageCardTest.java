package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import edu.mentorj.api.Citation;

import javax.swing.JEditorPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.List;

/**
 * Smoke test for the Swing side of a card: the HTML kit, the theme lookup and the width-aware layout must work
 * together without an exception. Exact pixels are left to the sandbox because they depend on the IDE LAF.
 */
public class MessageCardTest extends BasePlatformTestCase {

    public void testCompletedFooterDoesNotClipTheBody() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            MarkdownRenderer renderer = new MarkdownRenderer((code, language) -> List.of(), "复制");
            ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
            MessageCard card = new MessageCard(answer, renderer, () -> 500);
            answer.text("partial");
            card.refresh();
            card.setSize(card.getPreferredSize());
            card.doLayout();

            answer.done("""
                    你可以这样提问，我能帮你更多，例如：
                    1. 概念类：如 Spring 的 IoC/DI、Bean 作用域、AOP 代理方式、事务传播行为、Spring Boot 自动配置原理等。
                    2. 代码类：把报错信息、堆栈或相关代码贴出来（说明类名、依赖、JDK/框架版本），我按你选中的代码逐行分析原因。
                    3. 课程材料定位：告诉我关键词（如“事务传播”“自动配置”“JPA 映射”），我去课程材料里检索并标注来源文档与页码。
                    4. 实验/作业思路：我可以给思路和最小可运行示例，但不会声称替你跑过测试或改过文件——这些操作在本次对话中并未执行。
                    5. 补充一点更长的内容，用来验证卡片在完成态下的高度计算不会裁掉正文最后几行。
                    6. 再来一行，确保正文高于可视区域。
                    """, List.of(new Citation("Lec-5-Building-AI-Powered-Java-Applications.pdf", "p.37")), false);
            card.refresh();
            Dimension preferred = card.getPreferredSize();
            card.setSize(preferred);
            card.doLayout();
            JEditorPane body = findBody(card);
            assertTrue("card " + preferred + ", body bounds " + body.getBounds()
                            + ", body pref " + body.getPreferredSize(),
                    body.getHeight() >= body.getPreferredSize().height);
        });
    }

    private static JEditorPane findBody(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JEditorPane pane) {
                return pane;
            }
            if (child instanceof Container nested) {
                JEditorPane found = findBody(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

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
            ChatMessage wide = new ChatMessage(ChatMessage.Role.ASSISTANT, text);
            ChatMessage narrow = new ChatMessage(ChatMessage.Role.ASSISTANT, text);
            int wideHeight = new MessageCard(wide, renderer, () -> 700).getPreferredSize().height;
            int narrowHeight = new MessageCard(narrow, renderer, () -> 350).getPreferredSize().height;
            assertTrue("narrow card must be taller: wide=" + wideHeight + " narrow=" + narrowHeight,
                    narrowHeight > wideHeight);
        });
    }

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
