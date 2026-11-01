package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Retrieval quality regression: run the golden set against the real lecture decks and report the hit rates.
 *
 * <p>Why it is skipped by default: the decks live under {@code assets/} and are not in the repo, and the
 * semantic path also needs an API key, so missing either one makes the test unreproducible. CI skips it; with
 * a local environment configured it is the yardstick for before and after a retrieval change.
 *
 * <p>The thresholds are the measured values with headroom left below them, not guesses: a change that turns
 * this red really did regress.
 */
public class GoldenSetTest {

    private static final Path ASSETS = Path.of("assets");
    private static final int TOP_K = 5;
    /** Page-level hit rate floor. Measured 0.93, capped here with headroom, so turning this red means a regression. */
    private static final double MIN_PAGE_HIT_RATE = 0.85;

    @Test
    public void answersTheGoldenSet() throws IOException {
        assumeTrue("no lecture decks under assets/, skipping the golden set", Files.isDirectory(ASSETS));
        TongjiEmbeddingClient provider = TongjiEmbeddingClient.fromEnvironment();
        assumeTrue("" + TongjiEmbeddingClient.API_KEY_ENV + " is not configured, skipping the semantic evaluation",
                provider != null);

        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(null, provider);
        knowledgeBase.index(processCorpus());
        GoldenResult result = evaluate(knowledgeBase, questions());

        System.out.println(result.report());
        assertTrue("page-level hit rate: " + result.report(), result.pageHitRate() >= MIN_PAGE_HIT_RATE);
        assertEquals("negatives must all return empty results: " + result.report(), List.of(),
                result.leakedNegatives());
    }

    private static List<KnowledgeChunk> processCorpus() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(ASSETS, "*.pdf")) {
            stream.forEach(files::add);
        }
        files.sort(Comparator.naturalOrder());
        DocumentProcessor processor = new DocumentProcessor();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (Path file : files) {
            try {
                chunks.addAll(processor.process(file));
            } catch (DocumentProcessingException e) {
                throw new IllegalStateException("Failed to parse lecture deck: " + e.getMessage(), e);
            }
        }
        return chunks;
    }

    private static List<Question> questions() throws IOException {
        try (InputStream in = GoldenSetTest.class.getResourceAsStream("/golden/course-questions.tsv")) {
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            List<Question> questions = new ArrayList<>();
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("//")) {
                    continue;
                }
                String[] columns = line.split("\t");
                questions.add(new Question(columns[0], columns[1], columns[2]));
            }
            return questions;
        }
    }

    private static GoldenResult evaluate(RagKnowledgeBase knowledgeBase, List<Question> questions) {
        int pageHits = 0;
        int documentHits = 0;
        int positives = 0;
        double reciprocalRanks = 0;
        List<String> misses = new ArrayList<>();
        List<String> leaked = new ArrayList<>();
        for (Question question : questions) {
            List<KnowledgeChunk> hits = knowledgeBase.retrieve(question.text(), TOP_K);
            if (question.isNegative()) {
                if (!hits.isEmpty()) {
                    leaked.add(question.text() + " -> " + hits.get(0).document() + " " + hits.get(0).pageOrScope());
                }
                continue;
            }
            positives++;
            int rank = rankOf(hits, question);
            if (rank > 0) {
                pageHits++;
                reciprocalRanks += 1.0 / rank;
            } else {
                misses.add(question.text() + " (expected " + question.document() + " " + question.page() + ", "
                        + "got " + describe(hits) + ")");
            }
            if (hits.stream().anyMatch(hit -> hit.document().equals(question.document()))) {
                documentHits++;
            }
        }
        return new GoldenResult(positives, pageHits, documentHits, reciprocalRanks / Math.max(1, positives),
                misses, leaked);
    }

    /** @return the rank at which the expected page first appears (1-based); 0 when it is not hit */
    private static int rankOf(List<KnowledgeChunk> hits, Question question) {
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeChunk hit = hits.get(i);
            if (hit.document().equals(question.document()) && hit.pageOrScope().equals(question.page())) {
                return i + 1;
            }
        }
        return 0;
    }

    private static String describe(List<KnowledgeChunk> hits) {
        if (hits.isEmpty()) {
            return "no results";
        }
        return hits.stream().map(hit -> hit.document() + " " + hit.pageOrScope()).toList().toString();
    }

    private record Question(String text, String document, String page) {

        boolean isNegative() {
            return "-".equals(page);
        }
    }

    private record GoldenResult(int positives, int pageHits, int documentHits, double mrr,
                                List<String> misses, List<String> leakedNegatives) {

        double pageHitRate() {
            return positives == 0 ? 0 : (double) pageHits / positives;
        }

        double documentHitRate() {
            return positives == 0 ? 0 : (double) documentHits / positives;
        }

        String report() {
            return """
                    golden: %d positives / %d negatives
                     page-level top-%d hit rate %.2f (%d/%d)
                     document-level hit rate %.2f
                     MRR %.2f
                     misses: %s
                     leaked negatives: %s"""
                    .formatted(positives, leakedNegatives.size(), TOP_K, pageHitRate(), pageHits, positives,
                            documentHitRate(), mrr, misses, leakedNegatives);
        }
    }
}
