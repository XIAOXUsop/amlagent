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
        assertThat(parameters.customParameters())
                .doesNotContainKeys("prompt_cache_retention", "prompt_caching_retention");
    }

    @Test
    void stripsUnsupportedPromptCacheRetentionFromDeepSeekRequests() {
        CapturingChatModel delegate = new CapturingChatModel();
        var model = new DeepSeekCompatibleChatModel(delegate);
        var parameters = OpenAiChatRequestParameters.builder()
                .modelName("deepseek-v4-flash")
                .customParameters(Map.of(
                        "thinking", Map.of("type", "disabled"),
                        "prompt_cache_retention", "24h",
                        "prompt_caching_retention", "24h"))
                .build();
        var request = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(dev.langchain4j.data.message.UserMessage.from("test"))
                .parameters(parameters)
                .build();

        model.chat(request);

        var actual = (OpenAiChatRequestParameters) delegate.lastRequest.parameters();
        assertThat(actual.customParameters())
                .containsEntry("thinking", Map.of("type", "disabled"))
                .doesNotContainKeys("prompt_cache_retention", "prompt_caching_retention");
    }

    @Test
    void stripsUnsupportedPromptCacheRetentionFromDeepSeekStreamingRequests() {
        CapturingStreamingChatModel delegate = new CapturingStreamingChatModel();
        var model = new DeepSeekCompatibleStreamingChatModel(delegate);
        var parameters = OpenAiChatRequestParameters.builder()
                .modelName("deepseek-v4-flash")
                .customParameters(Map.of(
                        "thinking", Map.of("type", "disabled"),
                        "prompt_cache_retention", "24h"))
                .build();
        var request = dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(dev.langchain4j.data.message.UserMessage.from("test"))
                .parameters(parameters)
                .build();

        model.chat(request, new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        });

        var actual = (OpenAiChatRequestParameters) delegate.lastRequest.parameters();
        assertThat(actual.customParameters())
                .containsEntry("thinking", Map.of("type", "disabled"))
                .doesNotContainKey("prompt_cache_retention");
    }

    private static final class CapturingChatModel implements dev.langchain4j.model.chat.ChatModel {
        private dev.langchain4j.model.chat.request.ChatRequest lastRequest;

        @Override
        public dev.langchain4j.model.chat.response.ChatResponse doChat(
                dev.langchain4j.model.chat.request.ChatRequest request) {
            this.lastRequest = request;
            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("ok"))
                    .build();
        }
    }

    private static final class CapturingStreamingChatModel implements dev.langchain4j.model.chat.StreamingChatModel {
        private dev.langchain4j.model.chat.request.ChatRequest lastRequest;

        @Override
        public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                           dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
            this.lastRequest = request;
            handler.onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(dev.langchain4j.data.message.AiMessage.from("ok"))
                    .build());
        }
    }
}
