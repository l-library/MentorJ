package edu.mentorj.rag;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Connectivity check against the real endpoint: it reads the key from the environment and skips when none is
 * configured, so CI never fails on it.
 *
 * <p>It asserts only protocol-related properties (count, dimension, normalization), never specific values, so
 * the test does not go red when the model is updated.
 */
public class TongjiEmbeddingClientTest {

    private static TongjiEmbeddingClient clientOrSkip() {
        TongjiEmbeddingClient client = TongjiEmbeddingClient.fromEnvironment();
        assumeTrue("" + TongjiEmbeddingClient.API_KEY_ENV + " is not configured, skipping the real endpoint test",
                client != null);
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
            assertEquals("returned vectors must already be normalized", 1.0, Math.sqrt(norm), 1e-3);
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
            assertTrue("a missing data array must fail", false);
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("data"));
        }
    }
}
