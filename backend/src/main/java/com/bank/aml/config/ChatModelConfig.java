package com.bank.aml.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 根据 {@link LlmProperties} 构建当前激活的 {@link ChatModel}。
 * <p>提供商类型支持：OpenAI 兼容（DeepSeek/Qwen/OpenAI 走同一路径）、Anthropic、Mock。
 * 未配置 API Key 时自动降级到 {@link MockChatModel}，保证离线可演示。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class ChatModelConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatModelConfig.class);

    @Bean
    public ChatModel chatModel(LlmProperties props) {
        LlmProviderProperties active = props.active();
        String providerName = props.getActiveProvider();

        // 非 Mock 提供商若未配置 API Key，降级到 Mock 以保持可演示
        if (active.typeEnum() != LlmProperties.ProviderType.MOCK && !active.hasApiKey()) {
            log.warn("LLM 提供商 [{}] 未配置 API Key，降级到 Mock 模型。请设置 aml.llm.providers.{}.api-key", providerName, providerName);
            return new MockChatModel("mock-" + providerName);
        }

        switch (active.typeEnum()) {
            case ANTHROPIC -> {
                log.info("初始化 Anthropic 模型: provider={}, model={}", providerName, active.getModelName());
                var b = AnthropicChatModel.builder()
                        .apiKey(active.getApiKey())
                        .modelName(active.getModelName())
                        .temperature(active.getTemperature());
                if (active.getBaseUrl() != null && !active.getBaseUrl().isBlank()) {
                    b.baseUrl(active.getBaseUrl());
                }
                return b.build();
            }
            case MOCK -> {
                log.info("初始化 Mock 模型: model={}", active.getModelName());
                return new MockChatModel(active.getModelName() != null ? active.getModelName() : "mock");
            }
            default -> { // OPENAI_COMPATIBLE
                log.info("初始化 OpenAI 兼容模型: provider={}, model={} @ {}", providerName, active.getModelName(), active.getBaseUrl());
                var b = OpenAiChatModel.builder()
                        .apiKey(active.getApiKey())
                        .modelName(active.getModelName())
                        .temperature(active.getTemperature())
                        // DeepSeek 等支持并行工具调用
                        .parallelToolCalls(true);
                if (active.getBaseUrl() != null && !active.getBaseUrl().isBlank()) {
                    b.baseUrl(active.getBaseUrl());
                }
                return b.build();
            }
        }
    }
}
