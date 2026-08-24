package com.bank.aml.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdCardCipherTest {

    @AfterEach
    void restoreDevelopmentKey() {
        IdCardCipher.configure(IdCardCipher.DEV_KEY);
    }

    @Test
    void encryptsWithRandomizedAesGcmAndDecryptsLosslessly() throws Exception {
        String raw = "110101198506123456";
        String first = IdCardCipher.encrypt(raw);
        String second = IdCardCipher.encrypt(raw);

        assertThat(first).startsWith("enc:v1:").doesNotContain(raw);
        assertThat(second).isNotEqualTo(first);
        assertThat(IdCardCipher.decrypt(first)).isEqualTo(raw);
        assertThat(IdCardCipher.fingerprint(" 110101198506123456 "))
                .isEqualTo(IdCardCipher.fingerprint(raw));
        String plainSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        assertThat(IdCardCipher.fingerprint(raw)).isNotEqualTo(plainSha256);
    }

    @Test
    void rejectsMalformedOrWrongKeyInsteadOfReturningGarbage() {
        String encrypted = IdCardCipher.encrypt("ID-SECRET");
        IdCardCipher.configure("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");

        assertThatThrownBy(() -> IdCardCipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }
}
