package com.bank.aml.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigValidatorTest {

    private static final String STRONG_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String STRONG_PASSWORD = "secure-db-password-2026";

    private LlmProperties llmProperties(String type, String apiKey) {
        LlmProviderProperties provider = new LlmProviderProperties();
        provider.setType(type);
        provider.setModelName("test-model");
        provider.setApiKey(apiKey);
        LlmProperties props = new LlmProperties();
        props.setActiveProvider(type);
        props.setProviders(Map.of(type, provider));
        return props;
    }

    private ProductionConfigValidator validator(LlmProperties props, boolean allowFallback,
                                               boolean rerankEnabled, String modelSha, String tokenizerSha) {
        return new ProductionConfigValidator(STRONG_SECRET, STRONG_PASSWORD, true, true, "validate",
                props, allowFallback, rerankEnabled, modelSha, tokenizerSha);
    }

    @Test
    void rejectsMockModelWithoutFallback() {
        var v = validator(llmProperties("mock", null), false, false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真实模型 API Key");
    }

    @Test
    void rejectsMissingApiKeyWithoutFallback() {
        var v = validator(llmProperties("openai-compatible", ""), false, false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真实模型 API Key");
    }

    @Test
    void allowsMockWhenFallbackExplicitlyEnabled() {
        var v = validator(llmProperties("mock", null), true, false, "", "");
        // 显式允许 fallback 时不应因模型降级报错（但其他校验仍需满足，此处其他字段均合法）
        v.run(null);
    }

    @Test
    void rejectsRerankWithoutSha256() {
        var v = validator(llmProperties("openai-compatible", "test-key"), false, true, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void rejectsFlywayDisabled() {
        var v = new ProductionConfigValidator(STRONG_SECRET, STRONG_PASSWORD, true, false, "validate",
                llmProperties("openai-compatible", "test-key"), false, false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Flyway");
    }

    @Test
    void rejectsInsecureCookie() {
        var v = new ProductionConfigValidator(STRONG_SECRET, STRONG_PASSWORD, false, true, "validate",
                llmProperties("openai-compatible", "test-key"), false, false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Secure Cookie");
    }

    @Test
    void rejectsDdlAutoUpdate() {
        var v = new ProductionConfigValidator(STRONG_SECRET, STRONG_PASSWORD, true, true, "update",
                llmProperties("openai-compatible", "test-key"), false, false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validate");
    }
}
