package edu.mentorj;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.AgentResponse;
import edu.mentorj.api.Citation;
import edu.mentorj.api.DocumentLibrary;
import edu.mentorj.api.ImportResult;
import edu.mentorj.api.Statistics;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 组装点的接线验证：项目级 service 能否拿到 Project、索引是否落在 .idea、导入后 agent 能否
 * 把检索结果变成引用。
 *
 * <p>这是唯一能证明“平台注入 Project 构造参数”确实成立的检查，纯 JUnit 覆盖不到。
 */
public class MentorJProjectServiceTest extends BasePlatformTestCase {

    private Path documentDirectory;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // 索引落在项目的 .idea 下，会跨测试方法留存，每个用例都从空索引开始。
        library().clear();
        documentDirectory = Files.createTempDirectory("mentorj-service");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            library().clear();
        } finally {
            super.tearDown();
        }
    }

    public void testServiceIsWiredWithAProject() {
        MentorJProjectService service = MentorJProjectService.getInstance(getProject());

        assertNotNull(service.agentService());
        assertNotNull(service.documentLibrary());
    }

    public void testFreshProjectHasAnEmptyIndex() {
        Statistics statistics = library().statistics();

        assertEquals(List.of(), statistics.documents());
        assertEquals(0, statistics.chunks());
        assertNull(statistics.indexedAt());
    }

    public void testImportMakesRetrievalProduceCitations() throws IOException {
        ImportResult result = library().importDocuments(List.of(fixture("Lec-3.4-JUnit.pdf")), null);

        AgentResponse response = MentorJProjectService.getInstance(getProject()).agentService().respond(
                new AgentRequest(AgentAction.ASK, "what happens when I call reset", null, null));

        assertEquals(2, result.chunks());
        assertEquals(List.of(), result.failures());
        assertFalse("导入后不该走通用知识分支", response.generalKnowledgeOnly());
        Citation citation = response.citations().get(0);
        assertEquals("Lec-3.4-JUnit.pdf", citation.document());
        assertEquals("p.2", citation.pageOrScope());
    }

    public void testAskWithoutImportedMaterialFallsBackToGeneralKnowledge() {
        AgentResponse response = MentorJProjectService.getInstance(getProject()).agentService().respond(
                new AgentRequest(AgentAction.ASK, "how do I reset a counter", null, null));

        assertTrue(response.generalKnowledgeOnly());
        assertEquals(List.of(), response.citations());
    }

    public void testIndexIsWrittenUnderTheProjectIdeaDirectory() throws IOException {
        library().importDocuments(List.of(fixture("Lec-3.4-JUnit.pdf")), null);

        Path indexFile = Path.of(getProject().getBasePath(), ".idea", "mentorj-index.json");
        assertTrue(indexFile.toString(), Files.exists(indexFile));
    }

    private DocumentLibrary library() {
        return MentorJProjectService.getInstance(getProject()).documentLibrary();
    }

    private Path fixture(String name) {
        Path target = documentDirectory.resolve(name);
        try (InputStream in = getClass().getResourceAsStream("/documents/two-pages.pdf")) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }
}
