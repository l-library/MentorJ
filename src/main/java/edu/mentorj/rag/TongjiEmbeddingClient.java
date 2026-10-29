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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * 同济 AGI 平台的 OpenAI 兼容 embedding 客户端。
 *
 * <p>参数是实测出来的，不是猜的：单请求最多 8 条（16 条要 42 秒，32 条 180 秒不返回），平台套餐限
 * 2 个并发请求（第 3 个直接 429），因此这里用信号量把并发压到 2，并对 429/5xx 退避重试。
 *
 * <p>提问路径用 5 秒超时而不是 60 秒：接口挂了的时候，用户宁可用纯 BM25 的答案，也不想等着看转圈。
 */
public final class TongjiEmbeddingClient implements EmbeddingProvider {

    public static final String API_KEY_ENV = "MENTORJ_LLM_API_KEY";
    private static final String DEFAULT_BASE_URL = "https://agi.tongji.edu.cn/v1";
    private static final String MODEL = "Qwen3-Embedding";

    private static final int BATCH_SIZE = 8;
    private static final int MAX_CONCURRENCY = 2;
    private static final int MAX_RETRIES = 2;
    private static final Duration IMPORT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final String endpoint;
    private final String apiKey;
    private final int dimensions;
    private final int batchSize;
    private final HttpClient http;
    private final Semaphore permits = new Semaphore(MAX_CONCURRENCY);

    public TongjiEmbeddingClient(String baseUrl, String apiKey, int dimensions) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = base + "/embeddings";
        this.apiKey = apiKey;
        this.dimensions = dimensions;
        this.batchSize = BATCH_SIZE;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * 按环境变量构造。
     *
     * @return key 未配置时返回 {@code null}，检索自动只用 BM25
     */
    public static TongjiEmbeddingClient fromEnvironment() {
        String key = System.getenv(API_KEY_ENV);
        if (key == null || key.isBlank()) {
            return null;
        }
        String base = System.getenv("MENTORJ_LLM_BASE_URL");
        return new TongjiEmbeddingClient(base == null || base.isBlank() ? DEFAULT_BASE_URL : base, key, 512);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) throws IOException {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        // 批最多 4 批一起跑，真正卡住并发的是 permits 信号量。
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, batches(texts.size())), runnable -> {
            Thread thread = new Thread(runnable, "mentorj-embedding");
            thread.setDaemon(true);
            return thread;
        })) {
            List<Future<List<float[]>>> futures = new ArrayList<>();
            for (int start = 0; start < texts.size(); start += batchSize) {
                List<String> batch = texts.subList(start, Math.min(texts.size(), start + batchSize));
                futures.add(pool.submit(() -> request(batch, IMPORT_TIMEOUT)));
            }
            for (Future<List<float[]>> future : futures) {
                vectors.addAll(await(future));
            }
        }
        return vectors;
    }

    @Override
    public float[] embedQuery(String query) throws IOException {
        List<float[]> vectors = request(List.of(query), QUERY_TIMEOUT);
        if (vectors.size() != 1) {
            throw new IOException("期望 1 条向量，实际 " + vectors.size() + " 条");
        }
        return vectors.get(0);
    }

    private int batches(int count) {
        return (count + batchSize - 1) / batchSize;
    }

    private static List<float[]> await(Future<List<float[]>> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待向量化结果时被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("向量化失败：" + cause, cause);
        }
    }

    private List<float[]> request(List<String> texts, Duration timeout) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                sleep(backoffMillis(attempt));
            }
            permits.acquireUninterruptibly();
            try {
                return send(texts, timeout);
            } catch (RetryableException e) {
                lastFailure = new IOException(e.getMessage(), e);
            } finally {
                permits.release();
            }
        }
        throw lastFailure == null ? new IOException("向量化失败") : lastFailure;
    }

    private List<float[]> send(List<String> texts, Duration timeout) throws IOException, RetryableException {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("dimensions", dimensions);
        JsonArray input = new JsonArray();
        texts.forEach(input::add);
        body.add("input", input);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("向量化请求被中断", e);
        }
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            throw new RetryableException("HTTP " + response.statusCode() + "：" + response.body());
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + "：" + response.body());
        }
        return parse(response.body());
    }

    static List<float[]> parse(String json) throws IOException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonElement data = root.get("data");
        if (data == null || !data.isJsonArray()) {
            throw new IOException("响应缺少 data 数组：" + json);
        }
        List<float[]> vectors = new ArrayList<>();
        for (JsonElement element : data.getAsJsonArray()) {
            JsonArray embedding = element.getAsJsonObject().getAsJsonArray("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = embedding.get(i).getAsFloat();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static long backoffMillis(int attempt) {
        return 1000L * attempt;
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("退避等待被中断", e);
        }
    }

    /** 4xx/5xx 里值得重试的那一类，用异常类型把“可重试”和“真失败”分开。 */
    private static final class RetryableException extends Exception {

        RetryableException(String message) {
            super(message);
        }
    }

    /** 便于日志里不泄露 key。 */
    @Override
    public String toString() {
        return "TongjiEmbeddingClient{" + endpoint + ", model=" + MODEL + ", dimensions=" + dimensions + "}";
    }
}
