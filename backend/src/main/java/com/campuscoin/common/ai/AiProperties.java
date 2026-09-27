package com.campuscoin.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code campuscoin.ai} block of {@code application.yml}.
 *
 * <p><b>{@code apiKey} has no default and must come from the environment.</b> It is
 * {@code ${GEMINI_API_KEY:}} in the configuration file - an empty default rather than none, so the
 * application starts with no provider configured and simply runs on its rule-based paths. A key is
 * never written to this file, never committed, never put in the database, never put in a JWT, and
 * never sent to Angular. When it is blank, {@link AiConfig} installs
 * {@link NoopAiSuggestionPort} instead of the Gemini adapter, so "no key" is a supported
 * deployment rather than a startup failure - the difference from the encryption key, which has no
 * default and stops the application, is that a missing encryption key would corrupt data while a
 * missing AI key only means fewer suggestions.
 *
 * <p><b>{@code enabled} and {@code sendAggregatesOnly} are read from {@code system_settings} at the
 * point of use, not from here.</b> VĐ-05 makes them administrator-tunable, and
 * {@code ai.enabled} / {@code ai.send_aggregates_only} are the seeded rows (see {@code
 * db/05_seed.sql}). This record carries only the deployment's own configuration: the credential, the
 * endpoint, the model and the timeouts - values an administrator must not be able to change through
 * an API, because changing the model is changing where student data is sent.
 *
 * @param apiKey  the provider credential, from {@code GEMINI_API_KEY}. Blank means no provider.
 * @param model   the model identifier to call. Defaults to {@code gemini-3.5-flash}.
 * @param maxTokens the response cap for one call. Generous on purpose - see
 *                {@link #DEFAULT_MAX_TOKENS}. The calls stop at {@code STOP} once they are done, so
 *                a cap they never reach costs nothing.
 * @param baseUrl the API endpoint. Overridable so a test can point at a stub server; production
 *                leaves it unset and the SDK's own default applies.
 * @param timeoutSeconds how long to wait for one call before giving up. The caller treats a timeout
 *                as "no suggestion" and carries on, so this is a latency bound rather than an error.
 */
@ConfigurationProperties(prefix = "campuscoin.ai")
public record AiProperties(String apiKey,
                           String model,
                           Integer maxTokens,
                           String baseUrl,
                           Integer timeoutSeconds) {

    /**
     * The provider's general-purpose Flash model, which is the right tier for both calls this
     * application makes: each returns a sentence or two of prose, or a small typed object, from
     * context the caller has already reduced. A larger model would cost more per call and buy
     * nothing this workload can use.
     */
    public static final String DEFAULT_MODEL = "gemini-3.5-flash";

    /**
     * The response cap for one call.
     *
     * <p>This is a cap, not a target: both calls return a sentence or two or a small JSON object, so
     * only a fraction of it is ever used, and {@code finishReason=STOP} is the normal outcome.
     *
     * <p>It is deliberately not small. Gemini 3.x bills <b>thought tokens</b> against this same
     * budget ({@code usageMetadata.thoughtsTokenCount} plus {@code candidatesTokenCount} <= the cap),
     * and a reasoning model spends them before it writes anything - a one-word reply measured 84
     * thought tokens. At the previous value of 1024 the budget was occasionally consumed by thinking
     * alone, which truncated the reply mid-JSON ({@code finishReason=MAX_TOKENS}), made
     * {@link #parse} return null, and silently downgraded the answer to the rule-based text. That
     * failure is intermittent, so it reads as "the AI sometimes works" rather than as a defect; the
     * headroom here is what makes it deterministic.
     */
    public static final int DEFAULT_MAX_TOKENS = 8192;

    public static final int DEFAULT_TIMEOUT_SECONDS = 20;

    /** True when a credential is present, which is what decides whether the adapter is installed. */
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
