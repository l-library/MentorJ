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
}
