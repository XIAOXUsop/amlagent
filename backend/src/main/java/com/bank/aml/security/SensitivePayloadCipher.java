package com.bank.aml.security;

/** 加密归档载荷的统一入口；与身份字段使用同一受生产门禁保护的 AES-256 密钥。 */
public final class SensitivePayloadCipher {
    private SensitivePayloadCipher() {}
    public static String encrypt(String plaintext) { return IdCardCipher.encrypt(plaintext); }
    public static String decrypt(String ciphertext) { return IdCardCipher.decrypt(ciphertext); }
}
