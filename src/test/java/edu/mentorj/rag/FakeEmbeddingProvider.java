package edu.mentorj.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Embedding implementation for tests: the vectors come entirely from the function given at construction, so
 * "semantic ranking" can be asserted precisely without depending on what the model actually does.
 */
final class FakeEmbeddingProvider implements EmbeddingProvider {

    private final Function<String, float[]> vectorOf;
    private final int dimensions;
    private final List<List<String>> calls = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();
    private IOException failure;
    private IOException queryFailure;

    FakeEmbeddingProvider(int dimensions, Function<String, float[]> vectorOf) {
        this.dimensions = dimensions;
        this.vectorOf = vectorOf;
    }

    /** Text containing a keyword returns {@code hit}, everything else returns {@code miss}. */
    static FakeEmbeddingProvider matching(Map<String, float[]> vectors, float[] fallback) {
        return new FakeEmbeddingProvider(fallback.length, text -> {
            for (Map.Entry<String, float[]> entry : vectors.entrySet()) {
                if (text.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return fallback;
        });
    }

    /** The batch call during import fails. */
    FakeEmbeddingProvider failingWith(IOException failure) {
        this.failure = failure;
        return this;
    }

    /** The single call during a question fails, while the import still succeeds (the index holds vectors). */
    FakeEmbeddingProvider failingQueriesWith(IOException failure) {
        this.queryFailure = failure;
        return this;
    }

    List<String> queries() {
        return queries;
    }

    List<List<String>> calls() {
        return calls;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embedQuery(String query) throws IOException {
        queries.add(query);
        if (queryFailure != null) {
            throw queryFailure;
        }
        return vectorOf.apply(query);
    }

    @Override
    public List<float[]> embed(List<String> texts) throws IOException {
        calls.add(List.copyOf(texts));
        if (failure != null) {
            throw failure;
        }
        return texts.stream().map(vectorOf).toList();
    }
}
