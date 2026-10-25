package edu.mentorj.rag;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import edu.mentorj.api.KnowledgeBase;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.LlmService;
import edu.mentorj.api.Tool;

import java.util.List;

/** 检索工具：把知识库检索暴露给 agent 的工具调用循环。 */
public final class KnowledgeSearchTool implements Tool {

    private static final String NAME = "search_course_material";
    private static final int TOP_K = 5;

    private final KnowledgeBase knowledgeBase;

    public KnowledgeSearchTool(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public LlmService.ToolSpec spec() {
        return new LlmService.ToolSpec(
                NAME,
                "在课程材料知识库中检索与查询最相关的知识块，返回内容与来源（文档、页码/范围）。",
                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}");
    }

    @Override
    public String execute(String argumentsJson) {
        JsonElement arguments = JsonParser.parseString(argumentsJson == null ? "{}" : argumentsJson);
        JsonElement query = arguments.isJsonObject() ? arguments.getAsJsonObject().get("query") : null;
        if (query == null || !query.isJsonPrimitive()) {
            return "invalid arguments: missing \"query\"";
        }
        List<KnowledgeChunk> chunks = knowledgeBase.retrieve(query.getAsString(), TOP_K);
        if (chunks.isEmpty()) {
            return "no results";
        }
        StringBuilder result = new StringBuilder();
        for (KnowledgeChunk chunk : chunks) {
            result.append("[%s，页码/范围: %s]\n%s\n\n".formatted(chunk.document(), chunk.pageOrScope(), chunk.text()));
        }
        return result.toString().trim();
    }
}
