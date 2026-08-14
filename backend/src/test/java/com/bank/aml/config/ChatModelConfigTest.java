package com.bank.aml.config;

import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatModelConfigTest {

    @Test
    void disablesDeepSeekThinkingForMultiRoundToolCompatibility() {
        LlmProviderProperties provider = new LlmProviderProperties();
        provider.setType("openai-compatible");
        provider.setBaseUrl("https://api.deepseek.com");
        provider.setApiKey("test-only-key");
        provider.setModelName("deepseek-v4-flash");

        LlmProperties properties = new LlmProperties();
        properties.setActiveProvider("deepseek");
        properties.setProviders(Map.of("deepseek", provider));

        var model = new ChatModelConfig().chatModel(properties);
        var parameters = (OpenAiChatRequestParameters) model.defaultRequestParameters();

        assertThat(parameters.customParameters())
                .containsEntry("thinking", Map.of("type", "disabled"));
    }
}
