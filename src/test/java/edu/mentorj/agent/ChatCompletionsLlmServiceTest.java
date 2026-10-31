package edu.mentorj.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import edu.mentorj.api.AgentAction;
import edu.mentorj.api.AgentRequest;
import edu.mentorj.api.AgentResponse;
import edu.mentorj.api.Citation;
import edu.mentorj.api.KnowledgeChunk;
import edu.mentorj.api.LlmService;
import edu.mentorj.api.StreamListener;
import edu.mentorj.api.Tool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ChatCompletionsLlmServiceTest {

    private HttpServer server;
    private HttpClient http;
    private URI endpoint;
    private volatile int status;
    private volatile String response;
    private volatile String contentType;
    private volatile long delayMillis;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> accept = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<JsonObject> received = new AtomicReference<>();
    private final CountDownLatch releaseSlowStream = new CountDownLatch(1);

    @Before
    public void startServer() throws Exception {
        status = 200;
        contentType = "application/json";
        response = """
                {"choices":[{"finish_reason":"stop","message":{
                  "role":"assistant","content":"你好，Java！","tool_calls":null}}]}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            method.set(exchange.getRequestMethod());
            received.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/v1/stream/slow", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(("data: " + contentEvent("partial") + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                releaseSlowStream.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The client timed out and closed the stream, which is what this test expects.
            } finally {
                exchange.close();
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @After
    public void stopServer() {
        releaseSlowStream.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (http != null) {
            http.close();
        }
    }

    private ChatCompletionsLlmService service(Duration timeout) {
        return new ChatCompletionsLlmService(
                () -> new LlmSettings(endpoint, "test-secret", "course-model", timeout), http);
    }

    private LlmService.Request request() {
        return new LlmService.Request("你是课程助教。", List.of(LlmService.Message.user("解释 \"依赖注入\"\n举个例子")),
                List.of(new LlmService.ToolSpec("search_course_material", "课程检索", "{\"type\":\"object\"}")));
    }

    @Test
    public void sendsAuthenticatedUtf8RequestAndParsesText() {
        LlmService.Result result = service(Duration.ofSeconds(5)).complete(request());
        assertEquals("你好，Java！", result.content());
        assertTrue(result.toolCalls().isEmpty());
        assertEquals("Bearer test-secret", authorization.get());
        assertEquals("POST", method.get());
        JsonObject body = received.get();
        assertEquals("course-model", body.get("model").getAsString());
        assertEquals(8192, body.get("max_tokens").getAsInt());
        assertEquals(0.7, body.get("temperature").getAsDouble(), 0.000001);
        assertFalse(body.get("stream").getAsBoolean());
        assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("你是课程助教。",
                body.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
        assertEquals(request().messages().get(0).content(),
                body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString());
        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        assertEquals("search_course_material", tool.getAsJsonObject("function").get("name").getAsString());
        assertEquals("object",
                tool.getAsJsonObject("function").getAsJsonObject("parameters").get("type").getAsString());
        assertFalse(body.toString().contains("test-secret"));
    }

    @Test
    public void reportsHttpFailuresWithoutEchoingProviderBody() {
        response = "test-secret and sensitive provider error";
        for (int code : new int[]{401, 403, 429, 500, 302}) {
            status = code;
            LlmException error = assertThrows(LlmException.class,
                    () -> service(Duration.ofSeconds(5)).complete(request()));
            assertFalse(error.getMessage().contains("test-secret"));
            assertFalse(error.getMessage().contains("sensitive provider error"));
            assertTrue(error.getMessage().contains(code == 429 ? "限流" : code < 404 && code != 302 ? "认证" : "HTTP"));
        }
    }

    @Test
    public void rejectsMalformedEmptyAndFilteredResponses() {
        for (String body : new String[]{"<html>bad gateway</html>", "{}", "[]", "null", "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"content\":null}}]}",
                "{\"choices\":[{\"message\":{\"content\":42}}]}",
                "{\"choices\":[{\"message\":{\"content\":\" \"}}]}",
                "{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"filtered\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":null}}]}"}) {
            response = body;
            assertThrows(body, LlmException.class, () -> service(Duration.ofSeconds(5)).complete(request()));
        }
    }

    @Test
    public void preservesTruncatedAnswerAndSourcesWithVisibleWarning() {
        response = """
                {"choices":[{"finish_reason":"length","message":{
                  "role":"assistant","content":"构造器注入通过构造器传入依赖。```java class Example {",
                  "reasoning_content":"internal continuation data","tool_calls":null}}]}
                """;
        AgentLoop agent = new AgentLoop((query, topK) -> List.of(
                new KnowledgeChunk("Constructor injection", "Spring.pdf", "5", 1)), service(Duration.ofSeconds(5)),
                new SystemPromptBuilder(), new ToolRegistry());
        AgentResponse answer = agent.respond(new AgentRequest(AgentAction.ASK, "解释构造器注入", null, null));
        assertTrue(answer.text().startsWith("【回答未完成】"));
        assertTrue(answer.text().contains("构造器注入通过构造器传入依赖。```java class Example {"));
        assertFalse(answer.text().contains("internal continuation data"));
        assertEquals(List.of(new Citation("Spring.pdf", "5")), answer.citations());
        assertFalse(answer.generalKnowledgeOnly());
    }

    @Test
    public void reportsOutputBudgetWhenTruncationPrecedesAnswer() {
        for (String content : new String[]{"null", "\"\"", "\" \""}) {
            response = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":" + content
                    + ",\"reasoning_content\":\"internal continuation data\"}}]}";
            LlmException error = assertThrows(LlmException.class,
                    () -> service(Duration.ofSeconds(5)).complete(request()));
            assertTrue(error.getMessage().contains("8192"));
            assertTrue(error.getMessage().contains("尚未生成回答正文"));
            assertFalse(error.getMessage().contains("internal continuation data"));
        }
    }

    @Test
    public void neverExecutesTruncatedToolsEvenWhenSomeTextExists() {
        JsonObject body = JsonParser.parseString(toolResponse()).getAsJsonObject();
        JsonObject choice = body.getAsJsonArray("choices").get(0).getAsJsonObject();
        choice.addProperty("finish_reason", "length");
        JsonObject message = choice.getAsJsonObject("message");
        message.addProperty("content", "正在检索课程材料");
        message.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function")
                .addProperty("arguments", "{\"query\":\"");
        response = body.toString();
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public LlmService.ToolSpec spec() {
                return request().tools().get(0);
            }

            @Override
            public String execute(String arguments) {
                executions.incrementAndGet();
                return "material";
            }
        });
        AgentLoop agent = new AgentLoop((query, topK) -> List.of(), service(Duration.ofSeconds(5)),
                new SystemPromptBuilder(), registry);
        AgentResponse answer = agent.respond(new AgentRequest(AgentAction.ASK, "解释构造器注入", null, null));
        assertTrue(answer.text().contains("本轮工具尚未执行"));
        assertEquals(0, executions.get());
        assertFalse(answer.generalKnowledgeOnly());
    }

    @Test
    public void rejectsToolMessagesWithoutMatchingCalls() {
        LlmService.Request request = new LlmService.Request(
                "TA", List.of(LlmService.Message.tool("result")), List.of());
        assertThrows(LlmException.class, () -> service(Duration.ofSeconds(5)).complete(request));
    }

    private static String sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        return body.append("data: [DONE]\n\n").toString();
    }

    private static String contentEvent(String text) {
        return event(textDelta(text));
    }

    private static JsonObject textDelta(String text) {
        JsonObject delta = new JsonObject();
        delta.addProperty("content", text);
        return delta;
    }

    private static String finishEvent(String reason) {
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.addProperty("finish_reason", reason);
        choice.add("delta", new JsonObject());
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject event = new JsonObject();
        event.add("choices", choices);
        return event.toString();
    }

    private static String event(JsonObject delta) {
        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("delta", delta);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject event = new JsonObject();
        event.add("choices", choices);
        return event.toString();
    }

    private static JsonObject toolDelta(String id, String name, String arguments, int index) {
        JsonObject function = new JsonObject();
        if (name != null) {
            function.addProperty("name", name);
        }
        if (arguments != null) {
            function.addProperty("arguments", arguments);
        }
        JsonObject call = new JsonObject();
        call.addProperty("index", index);
        if (id != null) {
            call.addProperty("id", id);
            call.addProperty("type", "function");
        }
        call.add("function", function);
        JsonArray calls = new JsonArray();
        calls.add(call);
        JsonObject delta = new JsonObject();
        delta.add("tool_calls", calls);
        return delta;
    }

    private String toolResponse() {
        JsonObject body = JsonParser.parseString("""
                {"choices":[{"finish_reason":"tool_calls","message":{
                  "role":"assistant","content":null,"reasoning_content":"provider continuation data",
                  "tool_calls":[{"id":"call_search","type":"function","function":{
                    "name":"search_course_material","arguments":"{}"}}]}}]}
                """).getAsJsonObject();
        body.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message")
                .getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function")
                .addProperty("arguments", "{\"query\":\"JUnit\"}");
        return body.toString();
    }

    @Test
    public void roundTripsNativeCallsResultsAndReasoning() {
        response = toolResponse();
        LlmService.Result result = service(Duration.ofSeconds(5)).complete(request());
        assertEquals(List.of(new LlmService.ToolCall("call_search", "search_course_material", "{\"query\":\"JUnit\"}")),
                result.toolCalls());
        assertEquals("provider continuation data", result.reasoningContent());
        response = "{\"choices\":[{\"message\":{\"content\":\"使用 assertEquals\"},\"finish_reason\":\"stop\"}]}";
        LlmService.Result answer = service(Duration.ofSeconds(5)).complete(new LlmService.Request("TA",
                List.of(LlmService.Message.user("JUnit assertions"), LlmService.Message.assistant(result),
                        LlmService.Message.tool("call_search", "[JUnit.pdf, 3] Use assertEquals")), List.of()));
        assertEquals("使用 assertEquals", answer.content());
        JsonArray messages = received.get().getAsJsonArray("messages");
        JsonObject assistant = messages.get(2).getAsJsonObject();
        assertTrue(assistant.get("content").isJsonNull());
        assertEquals("provider continuation data", assistant.get("reasoning_content").getAsString());
        assertEquals("call_search", assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                .get("id").getAsString());
        assertEquals("call_search", messages.get(3).getAsJsonObject().get("tool_call_id").getAsString());
        assertFalse(received.get().has("tools"));
    }

    @Test
    public void rejectsInvalidCallMetadataAndDuplicateIds() {
        for (String mutation : List.of("missing-id", "duplicate", "arguments-object", "wrong-type", "truncated")) {
            JsonObject body = JsonParser.parseString(toolResponse()).getAsJsonObject();
            JsonObject choice = body.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonArray calls = choice.getAsJsonObject("message").getAsJsonArray("tool_calls");
            JsonObject call = calls.get(0).getAsJsonObject();
            switch (mutation) {
                case "missing-id" -> call.remove("id");
                case "duplicate" -> calls.add(call.deepCopy());
                case "arguments-object" -> call.getAsJsonObject("function").add("arguments", new JsonObject());
                case "wrong-type" -> call.addProperty("type", "shell");
                case "truncated" -> choice.addProperty("finish_reason", "length");
                default -> throw new AssertionError(mutation);
            }
            response = body.toString();
            assertThrows(mutation, LlmException.class, () -> service(Duration.ofSeconds(5)).complete(request()));
        }
    }

    @Test
    public void rejectsIncompleteToolHistoryBeforeSendingHttp() {
        LlmService.Result calls = new LlmService.Result(null,
                List.of(new LlmService.ToolCall("call_1", "search_course_material", "{}")));
        for (List<LlmService.Message> history : List.of(
                List.of(LlmService.Message.assistant(calls)),
                List.of(LlmService.Message.assistant(calls), LlmService.Message.tool("wrong-id", "result")),
                List.of(LlmService.Message.assistant(calls), LlmService.Message.user("continue")))) {
            assertThrows(LlmException.class, () -> service(Duration.ofSeconds(5)).complete(
                    new LlmService.Request("TA", history, List.of())));
        }
        assertNull(received.get());
    }

    @Test
    public void sendsConfiguredTokenBudget() {
        new ChatCompletionsLlmService(
                () -> new LlmSettings(endpoint, "test-secret", "course-model", Duration.ofSeconds(5), 4096), http)
                .complete(request());
        assertEquals(4096, received.get().get("max_tokens").getAsInt());
    }

    @Test
    public void streamsTextDeltasAndReturnsTheWholeAnswer() {
        contentType = "text/event-stream";
        response = sse(contentEvent("封装"), contentEvent("是隐藏实现"), finishEvent("stop"));
        List<String> deltas = new ArrayList<>();
        LlmService.Result result = service(Duration.ofSeconds(5)).complete(request(), deltas::add);
        assertEquals(List.of("封装", "是隐藏实现"), deltas);
        assertEquals("封装是隐藏实现", result.content());
        assertTrue(result.toolCalls().isEmpty());
        assertTrue(received.get().get("stream").getAsBoolean());
        assertEquals("text/event-stream", accept.get());
    }

    @Test
    public void assemblesToolCallFragmentsWithoutEmittingThemAsText() {
        contentType = "text/event-stream";
        response = sse(
                event(toolDelta("call_search", "search_course_material", "{\"query\":", 0)),
                event(toolDelta(null, null, "\"JUnit\"}", 0)),
                finishEvent("tool_calls"));
        List<String> deltas = new ArrayList<>();
        LlmService.Result result = service(Duration.ofSeconds(5)).complete(request(), deltas::add);
        assertTrue(deltas.isEmpty());
        assertNull(result.content());
        assertEquals(List.of(new LlmService.ToolCall("call_search", "search_course_material",
                "{\"query\":\"JUnit\"}")), result.toolCalls());
    }

    @Test
    public void fallsBackToTheWholeBodyWhenTheGatewayIgnoresStreaming() {
        List<String> deltas = new ArrayList<>();
        LlmService.Result result = service(Duration.ofSeconds(5)).complete(request(), deltas::add);
        assertEquals("你好，Java！", result.content());
        assertEquals(List.of("你好，Java！"), deltas);
        assertTrue(received.get().get("stream").getAsBoolean());
    }

    @Test
    public void disablesStreamingWhenTheSwitchIsOff() {
        LlmSettings settings = new LlmSettings(endpoint, "test-secret", "course-model",
                Duration.ofSeconds(5), 8192, false);
        List<String> deltas = new ArrayList<>();
        LlmService.Result result = new ChatCompletionsLlmService(() -> settings, http)
                .complete(request(), deltas::add);
        assertFalse(received.get().get("stream").getAsBoolean());
        assertEquals("你好，Java！", result.content());
        assertEquals(List.of("你好，Java！"), deltas);
    }

    @Test
    public void reportsStreamingFailuresWithoutLeakingProviderText() {
        contentType = "text/event-stream";
        response = sse(event(textDelta("shown")), finishEvent("length"));
        LlmService.Result partial = service(Duration.ofSeconds(5)).complete(request(), delta -> { });
        assertTrue(partial.content().startsWith("【回答未完成】"));
        assertTrue(partial.content().endsWith("shown"));
        for (String[] scenario : List.of(
                new String[]{sse(finishEvent("content_filter")), "过滤"},
                new String[]{sse(event(toolDelta("call_1", "search", "{", 0)), finishEvent("length")),
                        "本轮工具尚未执行"},
                new String[]{"data: not-json\n\n", "无效"},
                new String[]{"data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":[]}]}}]}\n\n", "无效"},
                new String[]{"data: {\"error\":{\"message\":\"secret provider detail\"}}\n\n", "无效"})) {
            response = scenario[0];
            LlmException error = assertThrows(scenario[0], LlmException.class,
                    () -> service(Duration.ofSeconds(5)).complete(request(), delta -> { }));
            assertTrue(error.getMessage().contains(scenario[1]));
            assertFalse(error.getMessage().contains("secret provider detail"));
        }
    }

    @Test
    public void stopsStreamingWhenTheListenerCancels() {
        contentType = "text/event-stream";
        response = sse(contentEvent("first"), contentEvent("second"));
        StreamListener listener = new StreamListener() {
            @Override
            public void onText(String delta) {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        LlmException error = assertThrows(LlmException.class,
                () -> service(Duration.ofSeconds(5)).complete(request(), listener));
        assertTrue(error.getMessage().contains("取消"));
    }

    @Test
    public void reportsStalledStreamsAsTimeouts() {
        LlmSettings settings = new LlmSettings(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/stream/slow"),
                "test-secret", "course-model", Duration.ofMillis(300));
        LlmException error = assertThrows(LlmException.class,
                () -> new ChatCompletionsLlmService(() -> settings, http)
                        .complete(request(), delta -> { }));
        assertTrue(error.getMessage().contains("超时"));
    }

    @Test
    public void enforcesRequestTimeout() {
        delayMillis = 500;
        LlmException error = assertThrows(LlmException.class,
                () -> service(Duration.ofMillis(100)).complete(request()));
        assertTrue(error.getMessage().contains("超时"));
    }

    @Test
    public void preservesThreadInterruption() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(LlmException.class, () -> service(Duration.ofSeconds(5)).complete(request()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
