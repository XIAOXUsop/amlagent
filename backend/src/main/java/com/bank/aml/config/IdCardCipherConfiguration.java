package com.bank.aml.config;

import com.bank.aml.common.crypto.IdCardCipher;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 在 JPA 实体开始处理新写入前装载字段加密密钥。 */
@Component
public class IdCardCipherConfiguration {

    private final String key;

    public IdCardCipherConfiguration(AmlProperties properties) {
        this.key = properties.security().fieldEncryptionKey();
    }

    @PostConstruct
    void configure() {
        IdCardCipher.configure(key);
    }

}
