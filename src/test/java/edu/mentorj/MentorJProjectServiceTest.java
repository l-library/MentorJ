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
 * Wiring verification for the assembly point: the project-level service gets the Project, the index lands in
 * .idea, and retrieval after an import returns course material with page numbers.
 *
 * <p>This is the only check proving that the platform really injects the Project constructor parameter, since
 * plain JUnit cannot cover it.
 *
 * <p>It deliberately does **not** go through {@code agentService()}: the answer body depends on an external
 * LLM and, by design, returns an error message without citations when no key is configured, so asserting on
 * it would bind the test to whether one particular provider is reachable. How citations are aggregated from
 * retrieval results into {@code AgentResponse} is the agent team's {@code AgentLoopTest}.
 */
public class MentorJProjectServiceTest extends BasePlatformTestCase {

    private Path documentDirectory;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The index lives under the project's .idea and survives across test methods, so each case starts empty.
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

        // The query uses words that really appear in the two-page fixture: the coverage gate was tuned against
        // the full decks of over two hundred chunks, and on this 2-page fixture it would match only 1/3 of the
        // query terms, which the gate judges as "not covered".
        List<KnowledgeChunk> hits = knowledgeBase().retrieve("counter reset semantics", 5);

        assertEquals(2, result.chunks());
        assertEquals(List.of(), result.failures());
        assertFalse("course material must be retrievable after an import", hits.isEmpty());
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
