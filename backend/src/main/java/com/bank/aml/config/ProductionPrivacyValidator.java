package com.bank.aml.config;

import com.bank.aml.security.IdCardCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 生产环境禁止使用仓库内的开发字段加密密钥。 */
@Component
@Profile("prod")
public class ProductionPrivacyValidator implements ApplicationRunner {
    private final String key;

    public ProductionPrivacyValidator(@Value("${aml.security.field-encryption-key:}") String key) {
        this.key = key;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (key == null || !key.matches("[0-9a-fA-F]{64}") || IdCardCipher.DEV_KEY.equalsIgnoreCase(key)) {
            throw new IllegalStateException("生产环境必须通过 AML_FIELD_ENCRYPTION_KEY 配置独立的 64 位十六进制 AES-256 密钥");
        }
    }
}
