package com.campuscoin.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "campuscoin.ai")
public record AiProperties(String apiKey,
                           String model,
                           Integer maxTokens,
                           String baseUrl,
                           Integer timeoutSeconds) {

    public static final String DEFAULT_MODEL = "gemini-3.5-flash";

    public static final int DEFAULT_MAX_TOKENS = 8192;

    public static final int DEFAULT_TIMEOUT_SECONDS = 20;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String effectiveModel() {
        return model == null || model.isBlank() ? DEFAULT_MODEL : model;
    }

    public int effectiveMaxTokens() {
        return maxTokens == null || maxTokens <= 0 ? DEFAULT_MAX_TOKENS : maxTokens;
    }

    public int effectiveTimeoutSeconds() {
        return timeoutSeconds == null || timeoutSeconds <= 0
                ? DEFAULT_TIMEOUT_SECONDS
                : timeoutSeconds;
    }
}
