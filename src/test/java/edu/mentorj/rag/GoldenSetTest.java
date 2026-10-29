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
 * 检索质量回归：拿真实课件跑 golden 集，输出命中率。
 *
 * <p>为什么默认跳过：课件在 {@code assets/} 下且不进仓库，语义那一路还需要 API key，两者缺一个
 * 就无法复现。CI 里这个测试会 skip，本地配好环境后它就是改动检索逻辑前后的对照尺。
 *
 * <p>阈值取的是实测值向下留出余量，不是拍脑袋：调参后变红说明确实退化了。
 */
public class GoldenSetTest {

    private static final Path ASSETS = Path.of("assets");
    private static final int TOP_K = 5;
    /** 页级命中率下限。实测 0.93，留出余量后卡在这里，调参后变红就说明退化了。 */
    private static final double MIN_PAGE_HIT_RATE = 0.85;

    @Test
    public void answersTheGoldenSet() throws IOException {
        assumeTrue("assets/ 下没有课件，跳过 golden 集", Files.isDirectory(ASSETS));
        TongjiEmbeddingClient provider = TongjiEmbeddingClient.fromEnvironment();
        assumeTrue("未配置 " + TongjiEmbeddingClient.API_KEY_ENV + "，跳过语义检索评测", provider != null);

        RagKnowledgeBase knowledgeBase = new RagKnowledgeBase(null, provider);
        knowledgeBase.index(processCorpus());
        GoldenResult result = evaluate(knowledgeBase, questions());

        System.out.println(result.report());
        assertTrue("页级命中率：" + result.report(), result.pageHitRate() >= MIN_PAGE_HIT_RATE);
        assertEquals("负例必须全部返回空结果：" + result.report(), List.of(), result.leakedNegatives());
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
                throw new IllegalStateException("课件解析失败：" + e.getMessage(), e);
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
                misses.add(question.text() + " (期望 " + question.document() + " " + question.page() + "，"
                        + "实际 " + describe(hits) + ")");
            }
            if (hits.stream().anyMatch(hit -> hit.document().equals(question.document()))) {
                documentHits++;
            }
        }
        return new GoldenResult(positives, pageHits, documentHits, reciprocalRanks / Math.max(1, positives),
                misses, leaked);
    }

    /** @return 期望页第一次出现的名次（1 起）；没命中返回 0 */
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
            return "空结果";
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
                    golden: %d 条正例 / %d 条负例
                     页级 top-%d 命中率 %.2f (%d/%d)
                     文档级命中率 %.2f
                     MRR %.2f
                     未命中: %s
                     负例漏出: %s"""
                    .formatted(positives, leakedNegatives.size(), TOP_K, pageHitRate(), pageHits, positives,
                            documentHitRate(), mrr, misses, leakedNegatives);
        }
    }
}
