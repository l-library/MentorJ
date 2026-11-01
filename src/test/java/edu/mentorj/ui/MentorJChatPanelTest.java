package edu.mentorj.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/**
 * Construction smoke test for the whole sidebar: the tool window factory builds this panel before the user sees
 * anything, so a failure here would leave the tool window empty.
 */
public class MentorJChatPanelTest extends BasePlatformTestCase {

    public void testPanelBuildsWithAnEmptyHistory() {
        ApplicationManager.getApplication().invokeAndWait(() -> {
            MentorJChatPanel panel = new MentorJChatPanel(getProject());
            panel.setSize(420, 700);
            panel.doLayout();
            assertTrue(panel.getComponentCount() >= 3);
        });
    }
}
