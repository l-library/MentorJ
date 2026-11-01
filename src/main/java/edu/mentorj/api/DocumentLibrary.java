package edu.mentorj.api;

import java.nio.file.Path;
import java.util.List;

/**
 * Document import, index clearing, and statistics: implemented by the RAG team, called by the UI team.
 *
 * <p>The methods block, so the UI team must call them on a background thread; they may run concurrently with
 * {@link KnowledgeBase#retrieve}.
 */
public interface DocumentLibrary {

    /**
     * Rebuild the whole index: clear the existing index -> parse each given document -> chunk -> build the
     * index -> write to disk.
     *
     * <p>Unsupported formats and parse failures are recorded in {@link ImportResult#failures()} instead of
     * thrown, and do not affect the other documents.
     *
     * @param documents documents to index; an empty list is equivalent to clearing the index
     * @param progress  progress callback; may be {@code null}
     * @return the result of this import, never {@code null}
     */
    ImportResult importDocuments(List<Path> documents, ProgressListener progress);

    /** Clear the index so the next retrieval returns an empty list. */
    void clear();

    /** Current index state; {@link Statistics#indexedAt()} is {@code null} before the first import. */
    Statistics statistics();
}
