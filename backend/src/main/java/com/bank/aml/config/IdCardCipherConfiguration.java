package com.bank.aml.config;

import com.bank.aml.security.IdCardCipher;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 在 JPA 实体开始处理新写入前装载字段加密密钥。 */
@Component
public class IdCardCipherConfiguration {
    private final String key;

    public IdCardCipherConfiguration(@Value("${aml.security.field-encryption-key}") String key) {
        this.key = key;
    }

    @PostConstruct
    void configure() {
        IdCardCipher.configure(key);
    }
}
