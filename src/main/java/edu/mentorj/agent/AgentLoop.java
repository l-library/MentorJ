package edu.mentorj.agent;

import edu.mentorj.api.*;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentService 实现：检索知识块 → 组装提示词 → 调 LLM → 工具循环 → 组装回答。
 * 知识块来源直接作为回答引用（citations），检索为空时标记 generalKnowledgeOnly。
 */
public final class AgentLoop implements AgentService {

    private static final int TOP_K = 5;
    private static final int MAX_TOOL_ROUNDS = 3;

    private final KnowledgeBase knowledgeBase;
    private final LlmService llmService;
    private final SystemPromptBuilder systemPromptBuilder;
    private final ToolRegistry toolRegistry;

    public AgentLoop(KnowledgeBase knowledgeBase,
                     LlmService llmService,
                     SystemPromptBuilder systemPromptBuilder,
                     ToolRegistry toolRegistry) {
        this.knowledgeBase = knowledgeBase;
        this.llmService = llmService;
        this.systemPromptBuilder = systemPromptBuilder;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public AgentResponse respond(AgentRequest request) {
        List<KnowledgeChunk> chunks = retrieve(request);
        List<LlmService.Message> messages = new ArrayList<>();
        messages.add(LlmService.Message.user(userPrompt(request, chunks)));

        LlmService.Result result = complete(request, messages);
        for (int round = 0; round < MAX_TOOL_ROUNDS && !result.toolCalls().isEmpty(); round++) {
            messages.add(LlmService.Message.assistant(result.content()));
            for (LlmService.ToolCall call : result.toolCalls()) {
                messages.add(LlmService.Message.tool(toolRegistry.execute(call)));
            }
            result = complete(request, messages);
        }
        return toResponse(result, chunks);
    }

    private List<KnowledgeChunk> retrieve(AgentRequest request) {
        if (request.userInput() == null || request.userInput().isBlank()) {
            return List.of();
        }
        return knowledgeBase.retrieve(request.userInput(), TOP_K);
    }

    private LlmService.Result complete(AgentRequest request, List<LlmService.Message> messages) {
        return llmService.complete(new LlmService.Request(
                systemPromptBuilder.build(request.action()), messages, toolRegistry.specs()));
    }

    private String userPrompt(AgentRequest request, List<KnowledgeChunk> chunks) {
        StringBuilder prompt = new StringBuilder();
        if (!chunks.isEmpty()) {
            prompt.append("以下是检索到的课程材料：\n");
            for (KnowledgeChunk chunk : chunks) {
                prompt.append("- [来源: %s，页码/范围: %s]\n%s\n"
                        .formatted(chunk.document(), chunk.pageOrScope(), chunk.text()));
            }
            prompt.append('\n');
        }
        if (request.editorContext() != null && request.editorContext().selectedCode() != null) {
            prompt.append("选中的代码：\n%s\n\n".formatted(request.editorContext().selectedCode()));
        }
        if (request.stagedDiff() != null) {
            prompt.append("staged 改动：\n%s\n\n".formatted(request.stagedDiff()));
        }
        if (request.userInput() != null) {
            prompt.append("用户输入：\n%s".formatted(request.userInput()));
        }
        return prompt.toString();
    }

    private AgentResponse toResponse(LlmService.Result result, List<KnowledgeChunk> chunks) {
        List<Citation> citations = chunks.stream()
                .map(chunk -> new Citation(chunk.document(), chunk.pageOrScope()))
                .distinct()
                .toList();
        return new AgentResponse(result.content(), citations, chunks.isEmpty());
    }
}
