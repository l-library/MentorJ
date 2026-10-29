package edu.mentorj;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import edu.mentorj.api.DocumentLibrary;
import edu.mentorj.api.ImportResult;
import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.Statistics;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 组装点的接线验证：项目级 service 能否拿到 Project、索引是否落在 .idea、导入后检索能否给出
 * 带页码的课程材料。
 *
 * <p>这是唯一能证明“平台注入 Project 构造参数”确实成立的检查，纯 JUnit 覆盖不到。
 *
 * <p>这里刻意**不**经过 {@code agentService()}：回答正文依赖外部 LLM，没配 key 时按设计返回
 * 错误说明且不带引用，断言它只会把测试绑在某个供应商是否可达上。引用如何从检索结果汇总进
 * {@code AgentResponse} 由 agent 组的 {@code AgentLoopTest} 负责。
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

    public void testImportMakesRetrievalReturnTheCitedPage() throws IOException {
        ImportResult result = library().importDocuments(List.of(fixture("Lec-3.4-JUnit.pdf")), null);

        // 查询用两页夹具里真实出现的词：覆盖率闸门是按两百多块的真实课件调的，在这个 2 页夹具上
        // 只会命中 1/3 的查询词，会被判成“没覆盖”。
        List<KnowledgeChunk> hits = knowledgeBase().retrieve("counter reset semantics", 5);

        assertEquals(2, result.chunks());
        assertEquals(List.of(), result.failures());
        assertFalse("导入后应当检索到课程材料", hits.isEmpty());
        assertEquals("Lec-3.4-JUnit.pdf", hits.get(0).document());
        assertEquals("p.2", hits.get(0).pageOrScope());
    }

    public void testRetrievalWithoutImportedMaterialIsEmpty() {
        assertEquals(List.of(), knowledgeBase().retrieve("how do I reset a counter", 5));
    }

    public void testIndexIsWrittenUnderTheProjectIdeaDirectory() throws IOException {
        library().importDocuments(List.of(fixture("Lec-3.4-JUnit.pdf")), null);

        Path indexFile = Path.of(getProject().getBasePath(), ".idea", "mentorj-index.json");
        assertTrue(indexFile.toString(), Files.exists(indexFile));
    }

    private DocumentLibrary library() {
        return MentorJProjectService.getInstance(getProject()).documentLibrary();
    }

    private KnowledgeBase knowledgeBase() {
        return MentorJProjectService.getInstance(getProject()).knowledgeBase();
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
