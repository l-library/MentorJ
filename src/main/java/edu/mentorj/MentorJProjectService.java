package edu.mentorj;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import edu.mentorj.agent.AgentLoop;
import edu.mentorj.agent.ChatCompletionsLlmService;
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
 * The assembly point: the only place allowed to depend on each module's concrete implementation, wiring the
 * knowledge base, the tools, and the agent core together.
 * The three modules collaborate only through the interfaces in edu.mentorj.api; see API.md.
 *
 * <p>The constructor only wires things up: it reads no disk and sends no request, because the project-level
 * service is created on the EDT and the index is loaded by {@link RagKnowledgeBase} on first use. Without a
 * configured {@code MENTORJ_LLM_API_KEY} the knowledge base has only the BM25 path, and behavior is unchanged.
 */
@Service(Service.Level.PROJECT)
public final class MentorJProjectService {

    /** Index file name, stored under the project's .idea directory (gitignored, so it never ships with the repo). */
    private static final String INDEX_FILE_NAME = "mentorj-index.json";

    private final AgentService agentService;
    private final DocumentLibrary documentLibrary;
    private final KnowledgeBase knowledgeBase;

    public MentorJProjectService(@NotNull Project project) {
        RagKnowledgeBase knowledgeBase =
                new RagKnowledgeBase(indexFile(project), TongjiEmbeddingClient.fromEnvironment());
        this.knowledgeBase = knowledgeBase;
        this.documentLibrary = new RagDocumentLibrary(knowledgeBase, new DocumentProcessor());

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new KnowledgeSearchTool(knowledgeBase));
        this.agentService = new AgentLoop(
                knowledgeBase, new ChatCompletionsLlmService(), new SystemPromptBuilder(), toolRegistry);
    }

    public AgentService agentService() {
        return agentService;
    }

    public DocumentLibrary documentLibrary() {
        return documentLibrary;
    }

    /** Retrieval entry point for callers that bypass the LLM, such as retrieval quality diagnostics. */
    public KnowledgeBase knowledgeBase() {
        return knowledgeBase;
    }

    /**
     * @return the index file path; {@code null} when the project has no base directory, in which case the
     *         index degrades to in-memory
     */
    private static @Nullable Path indexFile(Project project) {
        String basePath = project.getBasePath();
        return basePath == null ? null : Path.of(basePath, Project.DIRECTORY_STORE_FOLDER, INDEX_FILE_NAME);
    }

    public static MentorJProjectService getInstance(@NotNull Project project) {
        return project.getService(MentorJProjectService.class);
    }
}
