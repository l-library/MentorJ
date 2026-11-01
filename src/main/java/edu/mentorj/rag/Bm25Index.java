package edu.mentorj.rag;

import edu.mentorj.api.KnowledgeChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * BM25 lexical retrieval over a pure in-memory inverted index that is read-only once built.
 *
 * <p>Why it is the first retrieval path: no network, deterministic, explainable (it can name the terms that
 * matched), so neither a demo nor an evaluation breaks on a dropped connection or an exhausted quota.
 * Semantic recall comes from the embedding path, and {@link RagKnowledgeBase} fuses the two rankings.
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
        // Each term's postings are in ascending chunk order, so (chunk, term frequency) packs into one int array.
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
     * @return matched chunks in descending score order; chunks scoring 0 are dropped, since the caller would
     *         otherwise cite irrelevant material
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
     * One hit.
     *
     * @param chunk    chunk index
     * @param score    raw BM25 score (unbounded, used only for ordering and never exposed)
     * @param coverage what fraction of the query terms appear in this chunk, used by the relevance gate when
     *                 no vectors exist
     */
    record Hit(int chunk, double score, double coverage) {
    }
}
