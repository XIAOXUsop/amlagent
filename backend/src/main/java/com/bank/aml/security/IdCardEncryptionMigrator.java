package com.bank.aml.security;

import com.bank.aml.datasource.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** V6 兼容迁移：启动期间将历史明文证件号重写为 AES-GCM 密文并补齐检索指纹。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class IdCardEncryptionMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(IdCardEncryptionMigrator.class);
    private final CustomerRepository repository;

    public IdCardEncryptionMigrator(CustomerRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int migrated = 0;
        for (var customer : repository.findAll()) {
            if (!customer.isIdCardEncrypted()) {
                customer.setIdCard(customer.getIdCard());
                migrated++;
            } else {
                String expectedFingerprint = IdCardCipher.fingerprint(customer.getIdCard());
                if (!expectedFingerprint.equals(customer.getIdCardFingerprint())) {
                    customer.refreshIdCardFingerprint();
                    migrated++;
                }
            }
        }
        if (migrated > 0) {
            repository.flush();
            log.info("已完成 {} 条历史客户身份字段加密/检索指纹迁移", migrated);
        }
    }
}
