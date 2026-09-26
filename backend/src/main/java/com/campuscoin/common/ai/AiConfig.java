package com.campuscoin.common.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.campuscoin.common.setting.SettingReader;

/**
 * Chooses which {@link AiSuggestionPort} the application uses.
 *
 * <p>The same conditional-bean shape as {@code PasswordResetConfig}: the services that use the port
 * depend only on the interface, and which implementation is behind it stays a deployment concern.
 * Adding a second provider later means adding a bean here, not editing UC-08 or UC-17.
 *
 * <p><b>No credential means the no-op, not a failure to start.</b> The API key is deployment
 * configuration an operator may not have; without one, UC-08 uses the student's own
 * {@code category_rules} and UC-17 keeps the rule-based summary the database wrote. Starting anyway
 * is what lets the rest of module 12 - CSV import, anomaly detection, forecast, recent activity -
 * work in a deployment that has no AI provider at all.
 *
 * <p>The decision is made here and only here. No service checks for an API key, so no service can
 * disagree with this class about whether a provider exists.
 */
@Configuration
public class AiConfig {

    @Bean
    @ConditionalOnMissingBean(AiSuggestionPort.class)
    public AiSuggestionPort aiSuggestionPort(AiProperties properties, SettingReader settingReader) {
        if (!properties.isConfigured()) {
            return new NoopAiSuggestionPort();
        }
        return new GeminiAiSuggestionPort(properties, settingReader);
    }
}
