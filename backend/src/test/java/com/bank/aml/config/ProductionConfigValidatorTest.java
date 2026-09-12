package com.bank.aml.config;

import java.util.Map;
import org.junit.jupiter.api.Test;

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

    private ProductionConfigValidator validator(LlmProperties props, boolean rerankEnabled, String modelSha,
            String tokenizerSha) {
        return new ProductionConfigValidator(STRONG_SECRET, STRONG_PASSWORD, true, true, "validate", props,
                rerankEnabled, modelSha, tokenizerSha);
    }

    @Test
    void rejectsMockModelWithoutFallback() {
        var v = validator(llmProperties("mock", null), false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("真实模型 API Key");
    }

    @Test
    void rejectsMissingApiKeyWithoutFallback() {
        var v = validator(llmProperties("openai-compatible", ""), false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("真实模型 API Key");
    }

    @Test
    void rejectsMockEvenWhenLegacyFallbackFlagWouldHaveBeenEnabled() {
        var v = validator(llmProperties("mock", null), false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("禁止降级为 Mock");
    }

    @Test
    void rejectsRerankWithoutSha256() {
        var v = validator(llmProperties("openai-compatible", "test-key"), true, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("SHA-256");
    }

    @Test
    void rejectsFlywayDisabled() {
        var v = new ProductionConfigValidator(STRONG_SECRET, STRONG_PASSWORD, true, false, "validate",
                llmProperties("openai-compatible", "test-key"), false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("Flyway");
    }

    @Test
    void rejectsInsecureCookie() {
        var v = new ProductionConfigValidator(STRONG_SECRET, STRONG_PASSWORD, false, true, "validate",
                llmProperties("openai-compatible", "test-key"), false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Secure Cookie");
    }

    @Test
    void rejectsDdlAutoUpdate() {
        var v = new ProductionConfigValidator(STRONG_SECRET, STRONG_PASSWORD, true, true, "update",
                llmProperties("openai-compatible", "test-key"), false, "", "");
        assertThatThrownBy(() -> v.run(null)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("validate");
    }

}
