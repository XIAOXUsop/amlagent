package com.bank.aml.config;

import com.bank.aml.common.crypto.IdCardCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境默认凭据 fail-fast 校验：prod profile 下启动即拒绝所有仓库内置的演示默认值。
 * <ul>
 * <li>字段加密密钥：必须为独立的 64 位十六进制 AES-256 密钥（不得使用 dev 默认密钥）；</li>
 * <li>JWT 签名密钥：禁止使用仓库默认值与 "change-me" 占位串，且长度 ≥ 32 字符（HS256 强度）；</li>
 * <li>MySQL / PGVector 密码：禁止使用 compose 演示默认密码。</li>
 * </ul>
 */
@Component
@Profile("prod")
public class ProductionPrivacyValidator {

    private static final String DEFAULT_JWT_SECRET = "aml-agent-jwt-secret-2026-change-me-0123456789abcdef";

    private static final String DEFAULT_DB_PASSWORD = "aml123456";

    @Autowired
    public ProductionPrivacyValidator(AmlProperties aml, DatabaseDeploymentProperties database, RagProperties rag) {
        this(aml.security().fieldEncryptionKey(), aml.security().jwtSecret(), database.datasource().password(),
                rag.getPg().getPassword());
    }

    public ProductionPrivacyValidator(String fieldKey, String jwtSecret, String dbPassword, String pgPassword) {
        validateFieldKey(fieldKey);
        validateJwtSecret(jwtSecret);
        validateDbPassword("spring.datasource.password", dbPassword);
        validateDbPassword("aml.rag.pg.password", pgPassword);
    }

    private void validateFieldKey(String key) {
        if (key == null || !key.matches("[0-9a-fA-F]{64}") || IdCardCipher.DEV_KEY.equalsIgnoreCase(key)) {
            throw new IllegalStateException("生产环境必须通过 AML_FIELD_ENCRYPTION_KEY 配置独立的 64 位十六进制 AES-256 密钥");
        }
    }

    private void validateJwtSecret(String secret) {
        if (secret == null || secret.isBlank() || secret.length() < 32 || DEFAULT_JWT_SECRET.equalsIgnoreCase(secret)
                || secret.toLowerCase().contains("change-me")) {
            throw new IllegalStateException("生产环境必须通过 AML_JWT_SECRET 配置独立的 JWT 签名密钥（≥32 字符，不得使用默认/占位值）");
        }
    }

    private void validateDbPassword(String name, String password) {
        if (password == null || password.isBlank() || DEFAULT_DB_PASSWORD.equals(password)) {
            throw new IllegalStateException("生产环境禁止使用默认数据库密码（" + name + "）");
        }
    }

}
