package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
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
                    new MarkdownRenderer((code, language) -> List.of(), "复制"));
            transcript.setSize(520, 260);
            ChatMessage answer = new ChatMessage(ChatMessage.Role.ASSISTANT, "");
            transcript.add(answer);
            layoutTwice(transcript);

            answer.text("partial");
            transcript.refresh(answer);
            layoutTwice(transcript);

            answer.done("""
                    你可以这样提问，我能帮你更多，例如：
                    1. 概念类：如 Spring 的 IoC/DI、Bean 作用域、AOP 代理方式、事务传播行为、Spring Boot 自动配置原理等。
                    2. 代码类：把报错信息、堆栈或相关代码贴出来（说明类名、依赖、JDK/框架版本），我按你选中的代码逐行分析原因。
                    3. 课程材料定位：告诉我关键词（如“事务传播”“自动配置”“JPA 映射”），我去课程材料里检索并标注来源文档与页码。
                    4. 实验/作业思路：我可以给思路和最小可运行示例，但不会声称替你跑过测试或改过文件——这些操作在本次对话中并未执行。
                    5. 补充一点更长的内容，用来验证滚动条出现后卡片高度会重新计算。
                    6. 再来一行，确保正文高于可视区域。
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
