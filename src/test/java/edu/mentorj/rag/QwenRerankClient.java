package edu.mentorj.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cross-encoder rerank client for the Tongji AGI platform, used **only** by the offline rerank evaluation.
 *
 * <p>It is not wired into the question path: retrieval still runs BM25 plus embeddings plus RRF, and nothing in
 * {@code src/main} refers to this class. Whether a reranker earns a place there is exactly what
 * {@link RerankEvaluationTest} measures.
 *
 * <p>The request shape is the Jina/Cohere one, which is what the platform accepts (measured 2026-11-05):
 * {@code POST /v1/rerank} with {@code {"model", "query", "documents": [string]}} returns
 * {@code {"results": [{"index", "relevance_score"}]}}. Documents must be plain strings; the object form is
 * rejected with a 400.
 */
final class QwenRerankClient {

    static final String MODEL = "Qwen3-Reranker";
    private static final String DEFAULT_BASE_URL = "https://agi.tongji.edu.cn/v1";
    /**
     * A rerank call over ten candidates measured at 90-250 ms. The evaluation is offline, so this is generous
     * on purpose: a timeout here is a result, not a user waiting.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String endpoint;
    private final String apiKey;
    private final HttpClient http;

    private QwenRerankClient(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Build from the same environment variables as the embedding client.
     *
     * @return {@code null} when the key is not configured, in which case the evaluation skips itself
     */
    static QwenRerankClient fromEnvironment() {
        String key = System.getenv(TongjiEmbeddingClient.API_KEY_ENV);
        if (key == null || key.isBlank()) {
            return null;
        }
        String base = System.getenv("MENTORJ_LLM_BASE_URL");
        if (base == null || base.isBlank()) {
            base = DEFAULT_BASE_URL;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return new QwenRerankClient(trimmed + "/rerank", key);
    }

    /**
     * Score every document against the query.
     *
     * @return relevance scores in the order the documents were given, each in [0, 1]
     * @throws IOException the call failed, or the response did not carry one score per document
     */
    double[] scores(String query, List<String> documents) throws IOException {
        if (documents.isEmpty()) {
            return new double[0];
        }
        JsonArray payload = new JsonArray();
        documents.forEach(payload::add);
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("query", query);
        body.add("documents", payload);
        body.addProperty("top_n", documents.size());

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("rerank call interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("rerank returned HTTP " + response.statusCode() + ": " + response.body());
        }

        double[] scores = new double[documents.size()];
        boolean[] seen = new boolean[documents.size()];
        for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonObject()
                .getAsJsonArray("results")) {
            JsonObject result = element.getAsJsonObject();
            int index = result.get("index").getAsInt();
            if (index < 0 || index >= scores.length || seen[index]) {
                throw new IOException("rerank returned an out-of-range or duplicate index: " + index);
            }
            scores[index] = result.get("relevance_score").getAsDouble();
            seen[index] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) {
                throw new IOException("rerank returned no score for document " + i);
            }
        }
        return scores;
    }

    /** @return {@code documents} reordered by descending score, keeping the original order on ties */
    static <T> List<T> byDescendingScore(List<T> documents, double[] scores) {
        Map<Integer, Double> ranking = new TreeMap<>();
        for (int i = 0; i < scores.length; i++) {
            ranking.put(i, scores[i]);
        }
        return ranking.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> documents.get(entry.getKey()))
                .toList();
    }
}
