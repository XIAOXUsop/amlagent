package com.bank.aml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 多 LLM 提供商配置。通过 {@code aml.llm.active-provider} 切换默认提供商，
 * {@code aml.llm.providers} 定义各提供商连接参数（OpenAI 兼容 / Anthropic / Mock）。
 */
@ConfigurationProperties(prefix = "aml.llm")
public class LlmProperties {

    /** 当前激活的提供商 key，对应 {@link #providers} 中的键 */
    private String activeProvider = "deepseek";

    /** 提供商配置集合：key 为提供商名（如 deepseek/openai/qwen/claude/mock） */
    private Map<String, LlmProviderProperties> providers = new HashMap<>();

    public String getActiveProvider() {
        return activeProvider;
    }

    public void setActiveProvider(String activeProvider) {
        this.activeProvider = activeProvider;
    }

    public Map<String, LlmProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, LlmProviderProperties> providers) {
        this.providers = providers;
    }

    /** 当前激活的提供商配置 */
    public LlmProviderProperties active() {
        LlmProviderProperties p = providers.get(activeProvider);
        if (p == null) {
            throw new IllegalStateException("未找到 LLM 提供商配置: " + activeProvider
                    + "，请在 application.yml 的 aml.llm.providers 中定义");
        }
        return p;
    }

    /** 提供商类型 */
    public enum ProviderType {
        /** OpenAI 兼容接口（DeepSeek / Qwen / OpenAI 等） */
        OPENAI_COMPATIBLE,
        /** Anthropic Claude 原生接口 */
        ANTHROPIC,
        /** 本地 Mock 模型（无 API Key 时可演示完整流程） */
        MOCK
    }
}
