package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The retrieval golden set and the corpus it is annotated against, shared by the evaluation tests.
 *
 * <p>Neither lives in the repository: the lecture decks are under {@code assets/} and an evaluation also needs
 * the platform API key, so a test using this helper must skip itself when either is missing.
 */
final class GoldenSet {

    /** Where the lecture decks sit; not committed, so a clone has to bring its own. */
    static final Path ASSETS = Path.of("assets");

    private GoldenSet() {
    }

    /**
     * One annotated question.
     *
     * @param text     the question as a user would type it
     * @param document the deck expected to answer it
     * @param page     the expected page, or {@code "-"} when the decks do not cover the question
     */
    record Question(String text, String document, String page) {

        boolean isNegative() {
            return "-".equals(page);
        }
    }

    /** @return the annotated questions, with comment lines and blank lines dropped */
    static List<Question> questions() throws IOException {
        try (InputStream in = GoldenSet.class.getResourceAsStream("/golden/course-questions.tsv")) {
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

    /** @return every chunk of every deck under {@code assets}, in file name order */
    static List<KnowledgeChunk> corpus(Path assets) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(assets, "*.pdf")) {
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

    /** @return the 1-based rank at which the expected page first appears; 0 when it was not hit */
    static int rankOf(List<KnowledgeChunk> hits, Question question) {
        for (int i = 0; i < hits.size(); i++) {
            KnowledgeChunk hit = hits.get(i);
            if (hit.document().equals(question.document()) && hit.pageOrScope().equals(question.page())) {
                return i + 1;
            }
        }
        return 0;
    }

    static String describe(List<KnowledgeChunk> hits) {
        if (hits.isEmpty()) {
            return "no results";
        }
        return hits.stream().map(hit -> hit.document() + " " + hit.pageOrScope()).toList().toString();
    }
}
