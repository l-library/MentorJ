package edu.mentorj.agent;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/** Local configuration, read only when making a request so an unconfigured IDE can still start. */
record LlmSettings(URI endpoint, String apiKey, String model, Duration timeout, int maxTokens) {

    private static final String DEFAULT_BASE_URL = "https://agi.tongji.edu.cn/v1";
    private static final String DEFAULT_MODEL = "DeepSeek-V4.1-Flash";
    private static final int DEFAULT_MAX_TOKENS = 8192;

    LlmSettings(URI endpoint, String apiKey, String model, Duration timeout) {
        this(endpoint, apiKey, model, timeout, DEFAULT_MAX_TOKENS);
    }

    static LlmSettings fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static LlmSettings fromEnvironment(Map<String, String> environment) {
        String baseUrl = environment.containsKey("MENTORJ_LLM_BASE_URL")
                ? required(environment, "MENTORJ_LLM_BASE_URL") : DEFAULT_BASE_URL;
        String model = environment.containsKey("MENTORJ_LLM_MODEL")
                ? required(environment, "MENTORJ_LLM_MODEL") : DEFAULT_MODEL;
        String apiKey = required(environment, "MENTORJ_LLM_API_KEY");
        URI endpoint;
        try {
            String base = baseUrl.replaceAll("/+$", "");
            endpoint = URI.create(base.endsWith("/chat/completions") ? base : base + "/chat/completions");
            boolean local = "localhost".equals(endpoint.getHost()) || "127.0.0.1".equals(endpoint.getHost())
                    || "[::1]".equals(endpoint.getHost());
            if (endpoint.getHost() == null || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null
                    || !("https".equals(endpoint.getScheme()) || (local && "http".equals(endpoint.getScheme())))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new LlmException("llm.invalidUrl");
        }
        if (apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new LlmException("llm.invalidKey");
        }
        int maxTokens;
        try {
            maxTokens = Integer.parseInt(environment.getOrDefault(
                    "MENTORJ_LLM_MAX_TOKENS", Integer.toString(DEFAULT_MAX_TOKENS)).strip());
            if (maxTokens < 1 || maxTokens > 32768) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new LlmException("llm.invalidMaxTokens");
        }
        try {
            long seconds = Long.parseLong(environment.getOrDefault("MENTORJ_LLM_TIMEOUT_SECONDS", "90").strip());
            if (seconds < 1 || seconds > 600) {
                throw new NumberFormatException();
            }
            return new LlmSettings(endpoint, apiKey, model, Duration.ofSeconds(seconds), maxTokens);
        } catch (NumberFormatException e) {
            throw new LlmException("llm.invalidTimeout");
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new LlmException("llm.missingSetting", name);
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "LlmSettings[model=" + model + ", apiKey=<redacted>, timeout=" + timeout + "]";
    }
}
