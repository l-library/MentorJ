package edu.mentorj.api;

import java.util.List;

/**
 * The result of one import.
 *
 * @param documents names of the indexed documents
 * @param chunks    total number of indexed chunks
 * @param failures  failure reasons, each shaped {@code file name: reason}; an empty list on success
 */
public record ImportResult(List<String> documents, int chunks, List<String> failures) {
}
