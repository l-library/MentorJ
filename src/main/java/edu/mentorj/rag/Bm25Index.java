package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * BM25 词法检索，纯内存倒排表，构造后只读。
 *
 * <p>为什么用它做第一路：零网络、确定性、可解释（能说出命中哪些词），演示与评测都不会因为
 * 断网或配额而失效。语义召回由 embedding 那一路补，两路排名在 {@link RagKnowledgeBase} 里融合。
 */
final class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final int size;
    private final Map<String, int[]> postings;
    private final int[] lengths;
    private final double averageLength;

    private Bm25Index(int size, Map<String, int[]> postings, int[] lengths, double averageLength) {
        this.size = size;
        this.postings = postings;
        this.lengths = lengths;
        this.averageLength = averageLength;
    }

    static Bm25Index of(List<KnowledgeChunk> chunks) {
        List<Map<String, Integer>> termCounts = new ArrayList<>(chunks.size());
        Map<String, List<Integer>> collected = new HashMap<>();
        int[] lengths = new int[chunks.size()];
        long total = 0;
        for (int i = 0; i < chunks.size(); i++) {
            List<String> tokens = Tokenizer.tokenize(chunks.get(i).text());
            lengths[i] = tokens.size();
            total += tokens.size();
            Map<String, Integer> counts = new HashMap<>();
            for (String token : tokens) {
                counts.merge(token, 1, Integer::sum);
            }
            termCounts.add(counts);
            for (String term : counts.keySet()) {
                collected.computeIfAbsent(term, key -> new ArrayList<>()).add(i);
            }
        }
        Map<String, int[]> postings = new HashMap<>();
        // 每个词的出现记录按块号升序，因此 (块号, 词频) 可以压成一个定长数组。
        collected.forEach((term, indexes) -> {
            int[] flat = new int[indexes.size() * 2];
            for (int i = 0; i < indexes.size(); i++) {
                int chunk = indexes.get(i);
                flat[i * 2] = chunk;
                flat[i * 2 + 1] = termCounts.get(chunk).get(term);
            }
            postings.put(term, flat);
        });
        double average = chunks.isEmpty() ? 0 : (double) total / chunks.size();
        return new Bm25Index(chunks.size(), postings, lengths, average);
    }

    /**
     * @return 命中的块，按得分降序；得分为 0 的块不返回，否则调用方会把无关材料当成引用
     */
    List<Hit> search(String query, int limit) {
        Map<Integer, Double> scores = new HashMap<>();
        Map<Integer, Integer> matchedTerms = new HashMap<>();
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(Tokenizer.tokenize(query)));
        for (String term : terms) {
            int[] posting = postings.get(term);
            if (posting == null) {
                continue;
            }
            int documentFrequency = posting.length / 2;
            double idf = Math.log(1 + (size - documentFrequency + 0.5) / (documentFrequency + 0.5));
            for (int i = 0; i < posting.length; i += 2) {
                int chunk = posting[i];
                int frequency = posting[i + 1];
                double saturation = frequency + K1 * (1 - B + B * lengths[chunk] / averageLength);
                scores.merge(chunk, idf * frequency * (K1 + 1) / saturation, Double::sum);
                matchedTerms.merge(chunk, 1, Integer::sum);
            }
        }
        int termCount = Math.max(1, terms.size());
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> new Hit(entry.getKey(), entry.getValue(),
                        matchedTerms.getOrDefault(entry.getKey(), 0) / (double) termCount))
                .toList();
    }

    /**
     * 一条命中。
     *
     * @param chunk    块号
     * @param score    BM25 原始分（无上界，只用于排序，不对外暴露）
     * @param coverage 查询里有多少比例的词出现在这个块里，用于没有向量时的相关性闸门
     */
    record Hit(int chunk, double score, double coverage) {
    }
}
