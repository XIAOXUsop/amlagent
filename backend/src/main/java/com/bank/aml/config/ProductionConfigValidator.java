package com.bank.aml.config;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产环境配置校验：仅 prod Profile 激活。 启动时检查密钥长度与默认值，发现演示值直接拒绝启动，避免误部署。
 */
@Component
@Profile("prod")
public class ProductionConfigValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

    private final String jwtSecret;

    private final String dbPassword;

    private final boolean cookieSecure;

    private final boolean flywayEnabled;

    private final String ddlAuto;

    private final LlmProperties llmProperties;

    private final boolean rerankEnabled;

    private final String rerankModelSha256;

    private final String rerankTokenizerSha256;

    @Autowired
    public ProductionConfigValidator(AmlProperties aml, DatabaseDeploymentProperties database,
            LlmProperties llmProperties, RagProperties rag) {
        this(aml.security().jwtSecret(), database.datasource().password(), aml.security().cookieSecure(),
                database.flyway().enabled(), database.jpa().hibernate().ddlAuto(), llmProperties,
                rag.getRerank().isEnabled(), rag.getRerank().getModelSha256(), rag.getRerank().getTokenizerSha256());
    }

    public ProductionConfigValidator(String jwtSecret, String dbPassword, boolean cookieSecure, boolean flywayEnabled,
            String ddlAuto, LlmProperties llmProperties, boolean rerankEnabled, String rerankModelSha256,
            String rerankTokenizerSha256) {
        this.jwtSecret = jwtSecret;
        this.dbPassword = dbPassword;
        this.cookieSecure = cookieSecure;
        this.flywayEnabled = flywayEnabled;
        this.ddlAuto = ddlAuto;
        this.llmProperties = llmProperties;
        this.rerankEnabled = rerankEnabled;
        this.rerankModelSha256 = rerankModelSha256;
        this.rerankTokenizerSha256 = rerankTokenizerSha256;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = new ArrayList<>();
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.contains("change-me") || jwtSecret.length() < 32) {
            violations.add("JWT secret 使用默认值或长度不足（需 ≥32 字符且非 change-me）");
        }
        if (dbPassword == null || dbPassword.isBlank() || dbPassword.contains("aml123456")
                || dbPassword.contains("root123456")) {
            violations.add("数据库密码使用默认值");
        }
        if (!cookieSecure) {
            violations.add("生产环境必须启用 Secure Cookie（aml.security.cookie-secure=true）");
        }
        if (!flywayEnabled) {
            violations.add("生产环境必须启用 Flyway（spring.flyway.enabled=true）");
        }
        if (!"validate".equalsIgnoreCase(ddlAuto)) {
            violations.add("生产环境必须使用 ddl-auto=validate（当前=" + ddlAuto + "）");
        }
        LlmProviderProperties active = llmProperties.active();
        boolean mockOrMissingKey = active.typeEnum() == LlmProperties.ProviderType.MOCK || !active.hasApiKey();
        if (mockOrMissingKey) {
            violations.add("生产环境必须配置真实模型 API Key，且禁止降级为 Mock");
        }
        if (rerankEnabled && (rerankModelSha256.isBlank() || rerankTokenizerSha256.isBlank())) {
            violations.add("生产环境启用 rerank 时必须配置模型/分词器 SHA-256（aml.rag.rerank.model-sha256/tokenizer-sha256）");
        }
        if (!violations.isEmpty()) {
            String message = "生产环境配置校验失败：" + String.join("；", violations);
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("生产环境配置校验通过");
    }

}
