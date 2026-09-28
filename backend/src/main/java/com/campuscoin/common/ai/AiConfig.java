package com.campuscoin.common.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.campuscoin.common.setting.SettingReader;

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
