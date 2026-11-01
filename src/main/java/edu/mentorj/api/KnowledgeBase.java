package edu.mentorj.api;

import java.util.List;

/** Course material knowledge base: implemented by the RAG team, called by the agent core team. */
public interface KnowledgeBase {

    /**
     * Retrieve the chunks most relevant to the query.
     *
     * @param query query text
     * @param topK  maximum number of chunks to return
     * @return chunks in descending score order; an empty list when nothing matches, never {@code null}
     */
    List<KnowledgeChunk> retrieve(String query, int topK);

    /**
     * Context for a question the relevance gate rejects: the best-ranked chunks even though the corpus is judged
     * not to cover the question.
     *
     * <p>Why it exists: {@link #retrieve} answers "may I cite this", and that gate is a precision-first empirical
     * threshold, so a question can sit just below it while the ranking already put the page that answers it
     * first. Returning nothing then costs a question the material does cover. This method answers "may the model
     * look at this": the chunks inform the answer but are never cited, and the response still reports
     * general-knowledge only.
     *
     * @param query query text
     * @param topK  maximum number of chunks to return
     * @return chunks in descending score order, unverified; an empty list when nothing matches at all, never
     *         {@code null}
     */
    default List<KnowledgeChunk> lowConfidenceContext(String query, int topK) {
        // A default so implementations written before this method existed keep compiling; the cost is falling
        // back to "no context", which is exactly the old behaviour.
        return List.of();
    }
}
