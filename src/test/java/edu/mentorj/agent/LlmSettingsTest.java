package edu.mentorj.agent;

import org.junit.Test;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LlmSettingsTest {

    private Map<String, String> environment() {
        return new HashMap<>(Map.of(
                "MENTORJ_LLM_BASE_URL", "https://example.com/v1/",
                "MENTORJ_LLM_MODEL", "course-model",
                "MENTORJ_LLM_API_KEY", "test-secret"));
    }

    @Test
    public void usesTongjiDefaultsWhenOnlyApiKeyIsConfigured() {
        LlmSettings settings = LlmSettings.fromEnvironment(Map.of("MENTORJ_LLM_API_KEY", "test-secret"));
        assertEquals(URI.create("https://agi.tongji.edu.cn/v1/chat/completions"), settings.endpoint());
        assertEquals("DeepSeek-V4.1-Flash", settings.model());
        assertEquals(Duration.ofSeconds(90), settings.timeout());
        assertEquals(8192, settings.maxTokens());
    }

    @Test
    public void buildsEndpointAndKeepsCredentialsOutOfToString() {
        LlmSettings settings = LlmSettings.fromEnvironment(environment());
        assertEquals(URI.create("https://example.com/v1/chat/completions"), settings.endpoint());
        assertEquals(Duration.ofSeconds(90), settings.timeout());
        assertFalse(settings.toString().contains("test-secret"));
    }

    @Test
    public void acceptsFullEndpointAndCustomTimeout() {
        Map<String, String> environment = environment();
        environment.put("MENTORJ_LLM_BASE_URL", "https://example.com/api/v1/chat/completions");
        environment.put("MENTORJ_LLM_TIMEOUT_SECONDS", "120");
        LlmSettings settings = LlmSettings.fromEnvironment(environment);
        assertEquals("/api/v1/chat/completions", settings.endpoint().getPath());
        assertEquals(Duration.ofSeconds(120), settings.timeout());
    }

    @Test
    public void namesMissingSettingWithoutExposingOtherValues() {
        for (String name : environment().keySet()) {
            Map<String, String> environment = environment();
            environment.put(name, " ");
            LlmException error = assertThrows(LlmException.class, () -> LlmSettings.fromEnvironment(environment));
            assertTrue(error.getMessage().contains(name));
            assertFalse(error.getMessage().contains("test-secret"));
        }
    }

    @Test
    public void rejectsUnsafeOrMalformedUrls() {
        for (String url : new String[]{"http://example.com/v1", "ftp://example.com", "not a URL",
                "https://user:password@example.com", "https://example.com/v1?key=test-secret",
                "https://example.com/#fragment"}) {
            Map<String, String> environment = environment();
            environment.put("MENTORJ_LLM_BASE_URL", url);
            assertThrows(url, LlmException.class, () -> LlmSettings.fromEnvironment(environment));
        }
    }

    @Test
    public void permitsLoopbackHttpForLocalModelsAndTests() {
        Map<String, String> environment = environment();
        environment.put("MENTORJ_LLM_BASE_URL", "http://127.0.0.1:1234/v1");
        assertEquals("http", LlmSettings.fromEnvironment(environment).endpoint().getScheme());
    }

    @Test
    public void validatesTimeoutAndHeaderCharacters() {
        for (String timeout : new String[]{"0", "-1", "601", "oops"}) {
            Map<String, String> environment = environment();
            environment.put("MENTORJ_LLM_TIMEOUT_SECONDS", timeout);
            assertThrows(LlmException.class, () -> LlmSettings.fromEnvironment(environment));
        }
        Map<String, String> environment = environment();
        environment.put("MENTORJ_LLM_API_KEY", "secret\r\nInjected: value");
        assertThrows(LlmException.class, () -> LlmSettings.fromEnvironment(environment));
    }

    @Test
    public void validatesAndOverridesOutputTokenBudget() {
        Map<String, String> environment = environment();
        environment.put("MENTORJ_LLM_MAX_TOKENS", "4096");
        assertEquals(4096, LlmSettings.fromEnvironment(environment).maxTokens());
        environment.put("MENTORJ_LLM_MAX_TOKENS", "1000");
        assertEquals(1000, LlmSettings.fromEnvironment(environment).maxTokens());
        for (String tokens : new String[]{"", "0", "-1", "32769", "1.5", "oops"}) {
            environment.put("MENTORJ_LLM_MAX_TOKENS", tokens);
            assertThrows(LlmException.class, () -> LlmSettings.fromEnvironment(environment));
        }
    }
}
