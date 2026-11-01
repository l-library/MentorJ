package edu.mentorj.api;

/**
 * A course material chunk and its source.
 *
 * @param text        chunk content
 * @param document    source document
 * @param pageOrScope page number or range
 * @param score       relevance score, higher is more relevant
 */
public record KnowledgeChunk(String text, String document, String pageOrScope, double score) {
}
