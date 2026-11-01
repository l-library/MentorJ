package edu.mentorj.rag;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import edu.mentorj.api.Citation;
import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.LlmService;
import edu.mentorj.api.Tool;

import java.util.List;

/** Retrieval tool: exposes knowledge base retrieval to the agent's tool call loop. */
public final class KnowledgeSearchTool implements Tool {

    private static final String NAME = "search_course_material";
    private static final int TOP_K = 5;
    /** Unverified context is one chunk: the frame's budget and the risk of grounding on it both stay small. */
    private static final int LOW_CONFIDENCE_TOP_K = 1;

    private final KnowledgeBase knowledgeBase;

    public KnowledgeSearchTool(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public LlmService.ToolSpec spec() {
        return new LlmService.ToolSpec(
                NAME,
                "Search the course material knowledge base for the chunks most relevant to the query, "
                        + "returning their content and sources (document, page/scope).",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}");
    }

    @Override
    public String execute(String argumentsJson) {
        return executeWithSources(argumentsJson).text();
    }

    @Override
    public ExecutionResult executeWithSources(String argumentsJson) {
        JsonElement arguments = JsonParser.parseString(argumentsJson == null ? "{}" : argumentsJson);
        JsonElement query = arguments.isJsonObject() ? arguments.getAsJsonObject().get("query") : null;
        if (query == null || !query.isJsonPrimitive() || !query.getAsJsonPrimitive().isString()
                || query.getAsString().isBlank() || query.getAsString().length() > 4000) {
            return new ExecutionResult("tool_error: query must be a nonempty string of at most 4000 characters",
                    List.of());
        }
        List<KnowledgeChunk> chunks = knowledgeBase.retrieve(query.getAsString().strip(), TOP_K).stream()
                .limit(TOP_K).toList();
        if (!chunks.isEmpty()) {
            return rendered(chunks, true);
        }
        // The gate rejected the query, but the best-ranked chunk can still be the page that answers it. Offer it
        // without a source so the answer can use it and still cannot cite it.
        List<KnowledgeChunk> context = knowledgeBase
                .lowConfidenceContext(query.getAsString().strip(), LOW_CONFIDENCE_TOP_K).stream()
                .limit(LOW_CONFIDENCE_TOP_K).toList();
        return rendered(context, false);
    }

    /**
     * @param citable whether the chunks passed the relevance gate; when they did not, the text says so and the
     *                result carries no sources
     */
    private ExecutionResult rendered(List<KnowledgeChunk> chunks, boolean citable) {
        if (chunks.isEmpty()) {
            return new ExecutionResult("no results", List.of());
        }
        StringBuilder result = new StringBuilder();
        for (KnowledgeChunk chunk : chunks) {
            if (chunk.text() == null || chunk.text().isBlank() || chunk.document() == null || chunk.document().isBlank()
                    || chunk.pageOrScope() == null || chunk.pageOrScope().isBlank()) {
                return new ExecutionResult("tool_error: course material has incomplete content or source metadata",
                        List.of());
            }
            result.append("[%s, page/scope: %s]\n%s\n\n"
                    .formatted(chunk.document(), chunk.pageOrScope(), chunk.text()));
        }
        if (!citable) {
            return new ExecutionResult("the course material does not cover this query; the chunk below is "
                    + "unverified, may be unrelated, and must not be cited:\n\n" + result.toString().strip(),
                    List.of());
        }
        List<Citation> citations = chunks.stream().map(c -> new Citation(c.document(), c.pageOrScope()))
                .distinct().toList();
        return new ExecutionResult(result.toString().strip(), citations);
    }
}
