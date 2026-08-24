package com.bank.aml.config;

import com.bank.aml.security.IdCardCipher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionPrivacyValidatorTest {
    @Test
    void rejectsDevelopmentFieldEncryptionKey() {
        var validator = new ProductionPrivacyValidator(IdCardCipher.DEV_KEY);
        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AML_FIELD_ENCRYPTION_KEY");
    }

    @Test
    void acceptsIndependentAes256Key() {
        new ProductionPrivacyValidator(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789").run(null);
    }
}
