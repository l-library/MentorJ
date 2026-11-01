package edu.mentorj.api;

import java.time.Instant;
import java.util.List;

/**
 * Current index state.
 *
 * @param documents names of the indexed documents, sorted by name
 * @param chunks    total number of chunks
 * @param indexedAt time of the most recent import; {@code null} before the first import
 */
public record Statistics(List<String> documents, int chunks, Instant indexedAt) {
}
