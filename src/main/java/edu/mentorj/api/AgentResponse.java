package edu.mentorj.api;

import java.util.List;

/**
 * One answer the agent returns to the UI team.
 *
 * @param text                the answer body
 * @param citations           sources of the chunks given to the LLM; must be non-empty whenever material was retrieved
 * @param generalKnowledgeOnly {@code true} means no course material was retrieved, so the UI team must show
 *                             the general knowledge notice
 */
public record AgentResponse(String text, List<Citation> citations, boolean generalKnowledgeOnly) {
}
