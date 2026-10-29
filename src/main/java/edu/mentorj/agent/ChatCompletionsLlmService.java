package edu.mentorj.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import edu.mentorj.api.LlmService;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Supplier;

/** Non-streaming text and tool calls through a configurable Chat Completions endpoint. */
public final class ChatCompletionsLlmService implements LlmService {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Supplier<LlmSettings> settings;
    private final HttpClient http;

    public ChatCompletionsLlmService() {
        this(LlmSettings::fromEnvironment, HTTP);
    }

    ChatCompletionsLlmService(Supplier<LlmSettings> settings, HttpClient http) {
        this.settings = settings;
        this.http = http;
    }

    @Override
    public Result complete(Request request) {
        LlmSettings config = settings.get();
        HttpRequest httpRequest = HttpRequest.newBuilder(config.endpoint())
                .timeout(config.timeout())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(request, config), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            checkStatus(response.statusCode());
            return parse(response.body(), config.maxTokens());
        } catch (HttpTimeoutException e) {
            throw new LlmException("llm.timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("llm.interrupted");
        } catch (IOException e) {
            throw new LlmException("llm.networkError");
        }
    }

    private String payload(Request request, LlmSettings config) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("max_tokens", config.maxTokens());
        body.addProperty("temperature", 0.7);
        body.addProperty("stream", false);
        JsonArray messages = new JsonArray();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(message("system", request.systemPrompt()));
        }
        Set<String> pendingCalls = new HashSet<>();
        Set<String> seenCalls = new HashSet<>();
        for (Message item : request.messages()) {
            messages.add(serializeMessage(item, pendingCalls, seenCalls));
        }
        if (messages.isEmpty() || !pendingCalls.isEmpty()) {
            throw new LlmException("llm.unsupportedMessage");
        }
        body.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray definitions = new JsonArray();
            Set<String> names = new HashSet<>();
            for (ToolSpec spec : request.tools()) {
                if (spec.name() == null || !spec.name().matches("[a-zA-Z0-9_-]{1,64}") || !names.add(spec.name())) {
                    throw new LlmException("llm.unsupportedMessage");
                }
                JsonObject function = new JsonObject();
                function.addProperty("name", spec.name());
                function.addProperty("description", spec.description());
                try {
                    JsonObject schema = JsonParser.parseString(spec.parametersJsonSchema()).getAsJsonObject();
                    if (!"object".equals(string(schema, "type"))) {
                        throw new LlmException("llm.unsupportedMessage");
                    }
                    function.add("parameters", schema);
                } catch (RuntimeException e) {
                    throw new LlmException("llm.unsupportedMessage");
                }
                definitions.add(functionWrapper(function));
            }
            body.add("tools", definitions);
        }
        return body.toString();
    }

    private JsonObject serializeMessage(Message item, Set<String> pendingCalls, Set<String> seenCalls) {
        String role = item.role();
        if (role == null || !List.of("system", "user", "assistant", "tool").contains(role)
                || (!"tool".equals(role) && !pendingCalls.isEmpty())) {
            throw new LlmException("llm.unsupportedMessage");
        }
        boolean assistant = "assistant".equals(role);
        if ((!assistant && (!item.toolCalls().isEmpty() || item.reasoningContent() != null))
                || (!"tool".equals(role) && item.toolCallId() != null)
                || (item.content() == null && (!assistant || item.toolCalls().isEmpty()))) {
            throw new LlmException("llm.unsupportedMessage");
        }
        JsonObject message = message(role, item.content());
        if ("tool".equals(role)) {
            if (item.toolCallId() == null || !pendingCalls.remove(item.toolCallId())) {
                throw new LlmException("llm.unsupportedMessage");
            }
            message.addProperty("tool_call_id", item.toolCallId());
        }
        if (assistant && item.reasoningContent() != null) {
            message.addProperty("reasoning_content", item.reasoningContent());
        }
        if (!item.toolCalls().isEmpty()) {
            JsonArray calls = new JsonArray();
            for (ToolCall call : item.toolCalls()) {
                if (blank(call.id()) || blank(call.name()) || call.argumentsJson() == null
                        || !seenCalls.add(call.id())) {
                    throw new LlmException("llm.unsupportedMessage");
                }
                pendingCalls.add(call.id());
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name());
                function.addProperty("arguments", call.argumentsJson());
                JsonObject entry = functionWrapper(function);
                entry.addProperty("id", call.id());
                calls.add(entry);
            }
            message.add("tool_calls", calls);
        }
        return message;
    }

    private JsonObject functionWrapper(JsonObject function) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "function");
        wrapper.add("function", function);
        return wrapper;
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private void checkStatus(int status) {
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new LlmException("llm.authenticationError");
        }
        if (status == 429) {
            throw new LlmException("llm.rateLimited");
        }
        throw new LlmException("llm.httpError", Integer.toString(status));
    }

    private Result parse(String body, int maxTokens) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new LlmException("llm.invalidResponse");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            String finishReason = string(choice, "finish_reason");
            if ("content_filter".equals(finishReason)) {
                throw new LlmException("llm.filteredResponse");
            }
            JsonObject message = choice.getAsJsonObject("message");
            if (message == null) {
                throw new LlmException("llm.invalidResponse");
            }
            String role = string(message, "role");
            if (role != null && !"assistant".equals(role)) {
                throw new LlmException("llm.invalidResponse");
            }
            if ("length".equals(finishReason)) {
                return partialResponse(message, maxTokens);
            }
            List<ToolCall> calls = parseCalls(message);
            String content = string(message, "content");
            if (calls.isEmpty() && (blank(content) || "tool_calls".equals(finishReason))) {
                throw new LlmException("llm.invalidResponse");
            }
            return new Result(content, calls, string(message, "reasoning_content"));
        } catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
            throw new LlmException("llm.invalidResponse");
        }
    }

    private Result partialResponse(JsonObject message, int maxTokens) {
        JsonElement calls = message.get("tool_calls");
        // A length-limited response may contain incomplete function arguments. Never execute them.
        if (calls != null && !calls.isJsonNull() && (!calls.isJsonArray() || !calls.getAsJsonArray().isEmpty())) {
            throw new LlmException("llm.truncatedToolCall");
        }
        String content = string(message, "content");
        if (blank(content)) {
            // Reasoning-only output is not an answer and must not be shown as one.
            throw new LlmException("llm.truncatedResponse", Integer.toString(maxTokens));
        }
        String notice = ResourceBundle.getBundle("messages.MentorJBundle").getString("llm.partialResponse");
        // Put the notice first so an unfinished Markdown code fence cannot hide it.
        return new Result(notice + "\n\n" + content, List.of());
    }

    private List<ToolCall> parseCalls(JsonObject message) {
        List<ToolCall> calls = new ArrayList<>();
        JsonElement value = message.get("tool_calls");
        if (value == null || value.isJsonNull()) {
            return calls;
        }
        Set<String> ids = new HashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            JsonObject call = element.getAsJsonObject();
            String id = string(call, "id");
            JsonObject function = call.getAsJsonObject("function");
            if (!"function".equals(string(call, "type")) || blank(id) || !ids.add(id) || function == null) {
                throw new LlmException("llm.invalidResponse");
            }
            String name = string(function, "name");
            String arguments = string(function, "arguments");
            if (blank(name) || arguments == null) {
                throw new LlmException("llm.invalidResponse");
            }
            // Tool-specific validation reports malformed arguments back to the model for correction.
            calls.add(new ToolCall(id, name, arguments));
        }
        return calls;
    }

    private String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new LlmException("llm.invalidResponse");
        }
        return value.getAsString();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
