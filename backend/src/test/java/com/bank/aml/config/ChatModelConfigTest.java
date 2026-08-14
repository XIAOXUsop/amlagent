package com.bank.aml.config;

import com.bank.aml.observability.ChatModelTokenListener;
import com.bank.aml.observability.MetricsRecorder;
import com.bank.aml.observability.ModelPurposeContext;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

        var listener = new ChatModelTokenListener(new MetricsRecorder(new SimpleMeterRegistry()), new ModelPurposeContext());
        var model = new ChatModelConfig().chatModel(properties, listener);
        var parameters = (OpenAiChatRequestParameters) model.defaultRequestParameters();

        assertThat(parameters.customParameters())
                .containsEntry("thinking", Map.of("type", "disabled"));
    }
}
