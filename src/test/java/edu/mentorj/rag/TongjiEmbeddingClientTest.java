package edu.mentorj.rag;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * 真实端点的连通性检查：读环境变量里的 key，没配置时跳过（CI 里不会失败）。
 *
 * <p>只断言与协议相关的性质（条数、维度、归一化），不断言具体数值，避免测试跟着模型更新而变红。
 */
public class TongjiEmbeddingClientTest {

    private static TongjiEmbeddingClient clientOrSkip() {
        TongjiEmbeddingClient client = TongjiEmbeddingClient.fromEnvironment();
        assumeTrue("未配置 " + TongjiEmbeddingClient.API_KEY_ENV + "，跳过真实端点测试", client != null);
        return client;
    }

    @Test
    public void returnsOneNormalisedVectorPerInput() throws IOException {
        TongjiEmbeddingClient client = clientOrSkip();

        List<float[]> vectors = client.embed(List.of("JUnit runs @Test methods", "counter reset"));

        assertEquals(2, vectors.size());
        for (float[] vector : vectors) {
            assertEquals(client.dimensions(), vector.length);
            double norm = 0;
            for (float value : vector) {
                norm += value * value;
            }
            assertEquals("返回向量应已归一化", 1.0, Math.sqrt(norm), 1e-3);
        }
    }

    @Test
    public void handlesMoreInputsThanOneBatch() throws IOException {
        TongjiEmbeddingClient client = clientOrSkip();
        List<String> texts = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> "slide " + i + " about java exceptions")
                .toList();

        assertEquals(11, client.embed(texts).size());
    }

    @Test
    public void singleQueryVectorHasTheSameShape() throws IOException {
        TongjiEmbeddingClient client = clientOrSkip();

        assertEquals(client.dimensions(), client.embedQuery("what does @BeforeEach do").length);
    }

    @Test
    public void parsesAnOpenAiCompatibleResponse() throws IOException {
        String json = """
                {"object":"list","model":"Qwen3-Embedding","data":[
                  {"object":"embedding","index":0,"embedding":[0.5,-0.5,0.25]},
                  {"object":"embedding","index":1,"embedding":[0.0,1.0,-0.25]}]}""";

        List<float[]> vectors = TongjiEmbeddingClient.parse(json);

        assertEquals(2, vectors.size());
        assertEquals(3, vectors.get(0).length);
        assertEquals(0.5f, vectors.get(0)[0], 0f);
        assertEquals(-0.25f, vectors.get(1)[2], 0f);
    }

    @Test
    public void malformedResponsesAreReported() {
        try {
            TongjiEmbeddingClient.parse("{\"error\":\"quota\"}");
            assertTrue("缺少 data 必须报错", false);
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("data"));
        }
    }
}
