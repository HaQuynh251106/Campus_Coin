package com.campuscoin.common.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.campuscoin.common.setting.SettingReader;

@Configuration
public class ChatConfig {

    @Bean
    @ConditionalOnMissingBean(ChatCompletionPort.class)
    public ChatCompletionPort chatCompletionPort(AiProperties properties,
                                                 SettingReader settingReader) {
        if (!properties.isConfigured()) {
            return new NoopChatCompletionPort();
        }
        return new GeminiChatCompletionPort(properties, settingReader);
    }
}
