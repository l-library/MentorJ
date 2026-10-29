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
import edu.mentorj.api.Tool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
    private volatile long delayMillis;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<JsonObject> received = new AtomicReference<>();

    @Before
    public void startServer() throws Exception {
        status = 200;
        response = """
                {"choices":[{"finish_reason":"stop","message":{
                  "role":"assistant","content":"你好，Java！","tool_calls":null}}]}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            received.set(JsonParser.parseString(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
