package com.bank.aml.config;

import java.time.Duration;
import java.util.Optional;

/**
 * 单个 LLM 提供商连接参数。
 */
public class LlmProviderProperties {

    /** 提供商类型，默认 OpenAI 兼容 */
    private String type = "openai-compatible";

    /** API 基址（OpenAI 兼容端点，如 https://api.deepseek.com） */
    private String baseUrl;

    /** API Key（可用环境变量注入，如 ${DEEPSEEK_API_KEY:}） */
    private String apiKey;

    /** 模型名称（如 deepseek-chat / gpt-4o / qwen-plus / claude-sonnet-4-6） */
    private String modelName;

    /** 采样温度，金融场景默认较低以保证严谨 */
    private Double temperature = 0.2;

    /** 单次 LLM 调用超时（秒）；默认 60s 防止模型挂起无限阻塞 Worker */
    private int timeoutSeconds = 60;

    /** LLM 单次调用失败的最大自动重试次数（仅瞬时类错误），防止无限重试 */
    private int maxRetries = 2;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 单次 LLM 调用超时 Duration */
    public Duration timeout() {
        return Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public LlmProperties.ProviderType typeEnum() {
        return LlmProperties.ProviderType.valueOf(type.trim().toUpperCase().replace('-', '_'));
    }

    /** 是否为 OpenAPI 兼容类型（DeepSeek/Qwen/OpenAI 走同一条 OpenAI 兼容路径） */
    public boolean isOpenAiCompatible() {
        return typeEnum() == LlmProperties.ProviderType.OPENAI_COMPATIBLE;
    }

    /** 是否已配置有效 API Key */
    public boolean hasApiKey() {
        return Optional.ofNullable(apiKey).map(String::trim).filter(s -> !s.isEmpty()).isPresent();
    }
}
