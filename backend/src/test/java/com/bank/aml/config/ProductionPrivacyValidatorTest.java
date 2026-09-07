package com.bank.aml.config;

import com.bank.aml.security.IdCardCipher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionPrivacyValidatorTest {

    private static final String GOOD_JWT = "prod-jwt-secret-0123456789abcdef0123456789abcdef";

    @Test
    void rejectsDevelopmentFieldEncryptionKey() {
        assertThatThrownBy(() -> new ProductionPrivacyValidator(IdCardCipher.DEV_KEY, GOOD_JWT, "db-pass", "pg-pass"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AML_FIELD_ENCRYPTION_KEY");
    }

    @Test
    void acceptsIndependentAes256Key() {
        assertThatCode(() -> new ProductionPrivacyValidator(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                GOOD_JWT, "db-pass", "pg-pass")).doesNotThrowAnyException();
    }

    @Test
    void defaultOrPlaceholderJwtSecretIsRejected() {
        assertThatThrownBy(() -> new ProductionPrivacyValidator(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                "aml-agent-jwt-secret-2026-change-me-0123456789abcdef", "db-pass", "pg-pass"))
                .hasMessageContaining("AML_JWT_SECRET");
        assertThatThrownBy(() -> new ProductionPrivacyValidator(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                "my-own-secret-but-still-change-me-inside", "db-pass", "pg-pass"))
                .hasMessageContaining("AML_JWT_SECRET");
        assertThatThrownBy(() -> new ProductionPrivacyValidator(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                "too-short", "db-pass", "pg-pass"))
                .hasMessageContaining("AML_JWT_SECRET");
    }

    @Test
    void defaultDatabasePasswordsAreRejected() {
        String goodKey = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        assertThatThrownBy(() -> new ProductionPrivacyValidator(goodKey, GOOD_JWT, "aml123456", "pg-pass"))
                .hasMessageContaining("spring.datasource.password");
        assertThatThrownBy(() -> new ProductionPrivacyValidator(goodKey, GOOD_JWT, "db-pass", "aml123456"))
                .hasMessageContaining("aml.rag.pg.password");
        assertThatThrownBy(() -> new ProductionPrivacyValidator(goodKey, GOOD_JWT, "", "pg-pass"))
                .hasMessageContaining("spring.datasource.password");
    }
}