package edu.mentorj;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import edu.mentorj.agent.AgentLoop;
import edu.mentorj.agent.StubLlmService;
import edu.mentorj.agent.SystemPromptBuilder;
import edu.mentorj.agent.ToolRegistry;
import edu.mentorj.api.AgentService;
import edu.mentorj.api.DocumentLibrary;
import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.rag.DocumentProcessor;
import edu.mentorj.rag.KnowledgeSearchTool;
import edu.mentorj.rag.RagDocumentLibrary;
import edu.mentorj.rag.RagKnowledgeBase;
import edu.mentorj.rag.TongjiEmbeddingClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * 组装点：唯一允许依赖各模块具体实现的地方，把知识库、工具与 agent 核心接起来。
 * 三个模块只通过 edu.mentorj.api 的接口协作，见 API.md。
 *
 * <p>构造函数只做接线，不读磁盘也不发请求：项目级 service 在 EDT 上实例化，索引由
 * {@link RagKnowledgeBase} 在首次使用时加载。没有配置 {@code MENTORJ_LLM_API_KEY} 时知识库只有
 * BM25 一路，功能不变。
 */
@Service(Service.Level.PROJECT)
public final class MentorJProjectService {

    /** 索引文件名，放在项目的 .idea 下（已被 .gitignore 排除，不随仓库分发）。 */
    private static final String INDEX_FILE_NAME = "mentorj-index.json";

    private final AgentService agentService;
    private final DocumentLibrary documentLibrary;

    public MentorJProjectService(@NotNull Project project) {
        RagKnowledgeBase knowledgeBase =
                new RagKnowledgeBase(indexFile(project), TongjiEmbeddingClient.fromEnvironment());
        this.documentLibrary = new RagDocumentLibrary(knowledgeBase, new DocumentProcessor());

        KnowledgeBase retrieval = knowledgeBase;
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new KnowledgeSearchTool(retrieval));
        this.agentService = new AgentLoop(retrieval, new StubLlmService(), new SystemPromptBuilder(), toolRegistry);
    }

    public AgentService agentService() {
        return agentService;
    }

    public DocumentLibrary documentLibrary() {
        return documentLibrary;
    }

    /**
     * @return 索引文件路径；项目没有基础目录时返回 {@code null}，索引退化为内存态
     */
    private static @Nullable Path indexFile(Project project) {
        String basePath = project.getBasePath();
        return basePath == null ? null : Path.of(basePath, Project.DIRECTORY_STORE_FOLDER, INDEX_FILE_NAME);
    }

    public static MentorJProjectService getInstance(@NotNull Project project) {
        return project.getService(MentorJProjectService.class);
    }
}
