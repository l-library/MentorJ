package edu.mentorj.api;

/**
 * One source citation: the course material document and page / scope an answer cites.
 */
public record Citation(String document, String pageOrScope) {
}
