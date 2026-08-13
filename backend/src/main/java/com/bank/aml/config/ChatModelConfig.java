package com.bank.aml.config;

import com.bank.aml.observability.ChatModelTokenListener;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

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
    public ChatModel chatModel(LlmProperties props, ChatModelTokenListener tokenListener) {
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
                        .temperature(active.getTemperature())
                        .listeners(tokenListener);
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
                        .parallelToolCalls(true)
                        .listeners(tokenListener);
                if (active.getBaseUrl() != null && !active.getBaseUrl().isBlank()) {
                    b.baseUrl(active.getBaseUrl());
                }
                // DeepSeek V4 默认开启 thinking；工具调用的后续轮次要求完整回传 reasoning_content。
                // 当前 LangChain4j 链路以稳定、可复现的非思考模式运行，避免多轮工具调用返回 400。
                if ("deepseek".equalsIgnoreCase(providerName)
                        || (active.getBaseUrl() != null && active.getBaseUrl().contains("api.deepseek.com"))) {
                    b.customParameters(Map.of("thinking", Map.of("type", "disabled")));
                }
                return b.build();
            }
        }
    }

    /** 流式模型：用于报告分析过程的 token 级流式输出；无 API Key 时返回禁用实现（优雅降级） */
    @Bean
    public StreamingChatModel streamingChatModel(LlmProperties props, ChatModelTokenListener tokenListener) {
        LlmProviderProperties active = props.active();
        if (active.typeEnum() == LlmProperties.ProviderType.MOCK || !active.hasApiKey()) {
            log.warn("流式模型不可用（Mock 或无 API Key），流式输出降级为跳过");
            return new DisabledStreamingChatModel();
        }
        var b = OpenAiStreamingChatModel.builder()
                .apiKey(active.getApiKey())
                .modelName(active.getModelName())
                .temperature(active.getTemperature())
                .listeners(tokenListener);
        if (active.getBaseUrl() != null && !active.getBaseUrl().isBlank()) {
            b.baseUrl(active.getBaseUrl());
        }
        log.info("初始化流式模型: provider={}, model={}", props.getActiveProvider(), active.getModelName());
        return b.build();
    }
}
