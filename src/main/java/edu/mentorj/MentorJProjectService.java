package edu.mentorj;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import edu.mentorj.agent.AgentLoop;
import edu.mentorj.agent.ChatCompletionsLlmService;
import edu.mentorj.agent.SystemPromptBuilder;
import edu.mentorj.agent.ToolRegistry;
import edu.mentorj.api.AgentService;
import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.rag.KnowledgeSearchTool;
import edu.mentorj.rag.RagKnowledgeBase;
import org.jetbrains.annotations.NotNull;

/**
 * 组装点：唯一允许依赖各模块具体实现的地方，把知识库、工具与 agent 核心接起来。
 * 三个模块只通过 edu.mentorj.api 的接口协作，见 API.md。
 */
@Service(Service.Level.PROJECT)
public final class MentorJProjectService {

    private final AgentService agentService;

    public MentorJProjectService() {
        KnowledgeBase knowledgeBase = new RagKnowledgeBase();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new KnowledgeSearchTool(knowledgeBase));
        this.agentService = new AgentLoop(
                knowledgeBase, new ChatCompletionsLlmService(), new SystemPromptBuilder(), toolRegistry);
    }

    public AgentService agentService() {
        return agentService;
    }

    public static MentorJProjectService getInstance(@NotNull Project project) {
        return project.getService(MentorJProjectService.class);
    }
}
