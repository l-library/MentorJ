package edu.mentorj.api;

/**
 * Import progress callback. A plain Java type, which keeps the {@code api} package free of platform APIs.
 *
 * <p>Called on the import thread; the UI team switches back to the EDT itself when it needs to update the UI.
 */
@FunctionalInterface
public interface ProgressListener {

    /**
     * @param done    number of documents processed
     * @param total   total number of documents in this import
     * @param message name of the document being processed
     */
    void onProgress(int done, int total, String message);
}
