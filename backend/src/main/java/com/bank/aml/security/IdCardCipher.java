package com.bank.aml.security;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** 身份证件号字段级加密与确定性检索指纹；任何日志均不得输出输入或密文。 */
public final class IdCardCipher {
    public static final String DEV_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile SecretKeySpec key = keyFromHex(DEV_KEY);
    private static volatile SecretKeySpec fingerprintKey = fingerprintKeyFromHex(DEV_KEY);

    private IdCardCipher() {}

    public static void configure(String hexKey) {
        key = keyFromHex(hexKey);
        fingerprintKey = fingerprintKeyFromHex(hexKey);
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        if (isEncrypted(plaintext)) return plaintext;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("身份字段加密失败", e);
        }
    }

    public static String decrypt(String stored) {
        if (!isEncrypted(stored)) return stored; // 仅供 V6 上线时读取尚未重加密的历史值
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (payload.length < 29) throw new IllegalArgumentException("invalid encrypted payload");
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = java.util.Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("身份字段解密失败（密钥不匹配或数据损坏）", e);
        }
    }

    public static String fingerprint(String identityNumber) {
        if (identityNumber == null || identityNumber.isBlank()) return null;
        try {
            String normalized = identityNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("身份检索指纹计算失败", e);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static SecretKeySpec keyFromHex(String hexKey) {
        if (hexKey == null || !hexKey.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("AML_FIELD_ENCRYPTION_KEY 必须是 64 位十六进制 AES-256 密钥");
        }
        return new SecretKeySpec(HexFormat.of().parseHex(hexKey), "AES");
    }

    private static SecretKeySpec fingerprintKeyFromHex(String hexKey) {
        byte[] encryptionKey = keyFromHex(hexKey).getEncoded();
        try {
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(encryptionKey, "HmacSHA256"));
            byte[] derived = derivation.doFinal("aml-id-fingerprint-v1".getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(derived, "HmacSHA256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("身份检索指纹密钥派生失败", e);
        }
    }
}
