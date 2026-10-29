package edu.mentorj.rag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 测试用的向量化实现：向量完全由构造时给的函数决定，因此“语义排名”这件事可以被精确断言，
 * 而不必依赖模型的实际行为。
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

    /** 关键词命中的文本返回 {@code hit}，其余返回 {@code miss}。 */
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

    /** 导入时的批量调用失败。 */
    FakeEmbeddingProvider failingWith(IOException failure) {
        this.failure = failure;
        return this;
    }

    /** 提问时的单条调用失败，导入仍然成功（索引里有向量）。 */
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
