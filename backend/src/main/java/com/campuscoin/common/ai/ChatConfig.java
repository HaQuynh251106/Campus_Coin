package com.campuscoin.common.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.campuscoin.common.setting.SettingReader;

/**
 * Chooses which {@link ChatCompletionPort} the application uses.
 *
 * <p>The same conditional-bean shape as {@link AiConfig}, and deliberately a separate bean rather than a
 * third method on the existing port. The two ports answer to different failure policies - the suggestion
 * port falls back to a deterministic path and returns empty, the chat port has no fallback and throws -
 * so a service that needs one should not be handed the other by accident. Splitting them means the chat
 * service cannot reach the suggestion port at all, and a future provider for one does not silently become
 * the provider for the other.
 *
 * <p><b>Both read the same credential.</b> {@code GEMINI_API_KEY} and the {@code campuscoin.ai} block
 * configure both adapters, so a deployment either has a provider or does not - there is no arrangement
 * where categorisation has one and the conversation does not. Sharing {@link AiProperties} is what keeps
 * the model, the timeout and the endpoint stated once.
 *
 * <p><b>No credential means the refusing no-op, not a failure to start.</b> The rest of the application
 * - including the whole of module 12 - runs without a provider, and the chat endpoint says so rather
 * than inventing an answer.
 */
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
