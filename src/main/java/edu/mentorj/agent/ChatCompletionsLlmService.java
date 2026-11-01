package edu.mentorj.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import edu.mentorj.api.LlmService;
import edu.mentorj.api.StreamListener;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Text and tool calls through a configurable Chat Completions endpoint, streamed when enabled. */
public final class ChatCompletionsLlmService implements LlmService {

    private static final long WATCHDOG_INTERVAL_MILLIS = 250;
    private static final String DATA_PREFIX = "data:";
    private static final String DONE = "[DONE]";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** One idle watchdog serves every request; each task only compares timestamps and closes one stream. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MentorJ LLM idle watchdog");
        thread.setDaemon(true);
        return thread;
    });

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
                .POST(HttpRequest.BodyPublishers.ofString(payload(request, config, false), StandardCharsets.UTF_8))
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

    @Override
    public Result complete(Request request, StreamListener listener) {
        LlmSettings config = settings.get();
        if (!config.stream()) {
            // The switch exists for gateways that reject stream=true; keep one callback shape either way.
            return emitOnce(complete(request), listener);
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(config.endpoint())
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload(request, config, true), StandardCharsets.UTF_8))
                .build();
        // A request timeout would cut long answers, so streams rely on the idle watchdog instead.
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                checkStatus(response.statusCode());
                return isEventStream(response)
                        ? readEvents(body, config, listener)
                        : parseWholeBody(body, config, listener);
            }
        } catch (HttpTimeoutException e) {
            throw new LlmException("llm.timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("llm.interrupted");
        } catch (IOException e) {
            throw new LlmException("llm.networkError");
        }
    }

    private String payload(Request request, LlmSettings config, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.addProperty("max_tokens", config.maxTokens());
        body.addProperty("temperature", 0.7);
        body.addProperty("stream", stream);
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
        if (!request.thinking()) {
            // The gateway serves a reasoning model, and the switch lives in the chat template rather than in a
            // top-level OpenAI field. Unknown template arguments make a strict gateway reject the request, so it
            // is sent only when the caller asked for the pass to be off.
            JsonObject templateArguments = new JsonObject();
            templateArguments.addProperty("thinking", false);
            body.add("chat_template_kwargs", templateArguments);
        }
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
            return new Result(content, calls, reasoning(message));
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

    private boolean isEventStream(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type")
                .map(value -> value.toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
                .orElse(false);
    }

    /**
     * The gateway accepted stream=true but answered with a complete body. Parse it the usual way, except that an
     * SSE-looking body still goes through the event reader; some gateways stream without declaring the content type.
     */
    private Result parseWholeBody(InputStream body, LlmSettings config, StreamListener listener) throws IOException {
        String text = readBody(body, config.timeout());
        if (text.stripLeading().startsWith(DATA_PREFIX)) {
            return readEvents(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), config, listener);
        }
        return emitOnce(parse(text, config.maxTokens()), listener);
    }

    /** The fallback body is not covered by HttpRequest.timeout, so keep the same overall deadline here. */
    private String readBody(InputStream body, Duration timeout) throws IOException {
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            expired.set(true);
            closeQuietly(body);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (expired.get()) {
                throw new LlmException("llm.timeout");
            }
            throw e;
        } finally {
            watchdog.cancel(false);
        }
    }

    /** Only answer text is reported here; the agent loop announces tool rounds through onToolCalls. */
    private Result emitOnce(Result result, StreamListener listener) {
        if (listener.isCancelled() || !result.toolCalls().isEmpty() || result.content() == null) {
            return result;
        }
        listener.onText(result.content());
        return result;
    }

    private Result readEvents(InputStream body, LlmSettings config, StreamListener listener) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCallBuilder> calls = new TreeMap<>();
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        AtomicBoolean idle = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread reader = Thread.currentThread();
        long timeoutNanos = config.timeout().toNanos();
        // The stream has no per-read timeout, so close it from the outside when tokens stop arriving or the user stops.
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
            if (listener.isCancelled() || reader.isInterrupted()) {
                cancelled.set(true);
            } else if (System.nanoTime() - lastActivity.get() > timeoutNanos) {
                idle.set(true);
            } else {
                return;
            }
            closeQuietly(body);
        }, WATCHDOG_INTERVAL_MILLIS, WATCHDOG_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        String finishReason = null;
        boolean done = false;
        try {
            BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            while (true) {
                if (listener.isCancelled() || Thread.currentThread().isInterrupted()) {
                    throw new LlmException("llm.interrupted");
                }
                String line = lines.readLine();
                if (line == null) {
                    break;
                }
                lastActivity.set(System.nanoTime());
                if (line.isEmpty() || line.startsWith(":") || !line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                String data = line.substring(DATA_PREFIX.length()).strip();
                if (data.isEmpty()) {
                    continue;
                }
                if (DONE.equals(data)) {
                    done = true;
                    break;
                }
                JsonObject event = parseEvent(data);
                if (event.has("error") && !event.get("error").isJsonNull()) {
                    throw new LlmException("llm.invalidResponse");
                }
                JsonArray choices = event.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) {
                    continue;    // usage-only chunks carry no delta
                }
                JsonObject choice = choices.get(0).getAsJsonObject();
                String reason = string(choice, "finish_reason");
                if (reason != null) {
                    finishReason = reason;
                }
                JsonObject delta = choice.getAsJsonObject("delta");
                if (delta == null) {
                    continue;
                }
                String text = string(delta, "content");
                if (text != null && !text.isEmpty()) {
                    content.append(text);
                    listener.onText(text);
                }
                // Reasoning is only replayed to the provider on the next round, never shown as an answer.
                String thinking = reasoning(delta);
                if (thinking != null) {
                    reasoning.append(thinking);
                }
                mergeToolCalls(delta, calls);
            }
        } catch (IOException e) {
            if (cancelled.get()) {
                throw new LlmException("llm.interrupted");
            }
            if (idle.get()) {
                throw new LlmException("llm.timeout");
            }
            throw new LlmException("llm.networkError");
        } catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException e) {
            throw new LlmException("llm.invalidResponse");
        } finally {
            watchdog.cancel(false);
        }
        if (cancelled.get()) {
            throw new LlmException("llm.interrupted");
        }
        if (idle.get()) {
            throw new LlmException("llm.timeout");
        }
        if (!done && finishReason == null) {
            throw new LlmException("llm.invalidResponse");
        }
        if (finishReason == null) {
            // Tolerate servers that close the stream right after [DONE] without a final finish_reason chunk.
            finishReason = content.isEmpty() && calls.isEmpty() ? null : "stop";
        }
        if ("content_filter".equals(finishReason)) {
            throw new LlmException("llm.filteredResponse");
        }
        if ("length".equals(finishReason)) {
            if (!calls.isEmpty()) {
                throw new LlmException("llm.truncatedToolCall");
            }
            if (content.isEmpty()) {
                throw new LlmException("llm.truncatedResponse", Integer.toString(config.maxTokens()));
            }
            String notice = ResourceBundle.getBundle("messages.MentorJBundle").getString("llm.partialResponse");
            return new Result(notice + "\n\n" + content, List.of(),
                    reasoning.isEmpty() ? null : reasoning.toString());
        }
        List<ToolCall> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ToolCallBuilder call : calls.values()) {
            if (blank(call.id) || blank(call.name) || !call.argumentsSeen || !ids.add(call.id)) {
                throw new LlmException("llm.invalidResponse");
            }
            result.add(new ToolCall(call.id, call.name, call.arguments.toString()));
        }
        if (result.isEmpty() && content.isEmpty()) {
            throw new LlmException("llm.invalidResponse");
        }
        return new Result(content.isEmpty() ? null : content.toString(), result,
                reasoning.isEmpty() ? null : reasoning.toString());
    }

    private JsonObject parseEvent(String data) {
        try {
            JsonElement element = JsonParser.parseString(data);
            if (!element.isJsonObject()) {
                throw new LlmException("llm.invalidResponse");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new LlmException("llm.invalidResponse");
        }
    }

    /** Tool call deltas are keyed by index; id, name and argument fragments arrive across several chunks. */
    private void mergeToolCalls(JsonObject delta, Map<Integer, ToolCallBuilder> calls) {
        JsonElement value = delta.get("tool_calls");
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (!value.isJsonArray()) {
            throw new LlmException("llm.invalidResponse");
        }
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new LlmException("llm.invalidResponse");
            }
            JsonObject call = element.getAsJsonObject();
            int index = 0;
            JsonElement indexValue = call.get("index");
            if (indexValue != null && !indexValue.isJsonNull()) {
                if (!indexValue.isJsonPrimitive() || !indexValue.getAsJsonPrimitive().isNumber()) {
                    throw new LlmException("llm.invalidResponse");
                }
                index = indexValue.getAsInt();
                if (index < 0 || index > 63) {
                    throw new LlmException("llm.invalidResponse");
                }
            }
            String type = string(call, "type");
            if (type != null && !"function".equals(type)) {
                throw new LlmException("llm.invalidResponse");
            }
            ToolCallBuilder builder = calls.computeIfAbsent(index, key -> new ToolCallBuilder());
            String id = string(call, "id");
            if (id != null) {
                builder.id = id;
            }
            JsonObject function = call.getAsJsonObject("function");
            if (function == null) {
                continue;
            }
            String name = string(function, "name");
            if (name != null) {
                builder.name = name;
            }
            String arguments = string(function, "arguments");
            if (arguments != null) {
                builder.argumentsSeen = true;
                builder.arguments.append(arguments);
            }
        }
    }

    private void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // The read loop reports the failure through the usual error mapping.
        }
    }

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private boolean argumentsSeen;
        private final StringBuilder arguments = new StringBuilder();
    }

    /**
     * Reasoning text of one message or delta. OpenAI-compatible gateways send it as {@code reasoning_content},
     * while the Tongji deployment names the field {@code reasoning}; both are read so the text is kept for the
     * next tool round and counted as reasoning instead of vanishing.
     */
    private String reasoning(JsonObject object) {
        String thinking = string(object, "reasoning_content");
        if (thinking != null && !thinking.isEmpty()) {
            return thinking;
        }
        // The fallback field has no contract, so a non-string value is ignored instead of failing the answer.
        JsonElement fallback = object.get("reasoning");
        if (fallback == null || !fallback.isJsonPrimitive() || !fallback.getAsJsonPrimitive().isString()) {
            return null;
        }
        return fallback.getAsString();
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
