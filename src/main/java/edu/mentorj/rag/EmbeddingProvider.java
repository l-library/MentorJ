package edu.mentorj.rag;

import java.io.IOException;
import java.util.List;

/**
 * Text vectorization, a RAG-internal interface.
 *
 * <p>Deliberately not in {@code edu.mentorj.api}: it is not a contract between modules, and neither the UI
 * team nor the agent team needs to know which embedding model retrieval uses, or whether it uses one at all.
 */
public interface EmbeddingProvider {

    /** Vector dimension, which must match the vectors stored in the index. */
    int dimensions();

    /**
     * Batch vectorization, used on import.
     *
     * @return vectors with the same length and order as {@code texts}
     */
    List<float[]> embed(List<String> texts) throws IOException;

    /**
     * Single-input vectorization, used on a question. The default implementation goes through the batch
     * call; implementations may give it a shorter timeout, because every extra second on the question path
     * is visible to the user.
     */
    default float[] embedQuery(String query) throws IOException {
        List<float[]> vectors = embed(List.of(query));
        if (vectors.size() != 1) {
            throw new IOException("expected 1 vector but got " + vectors.size());
        }
        return vectors.get(0);
    }
}
