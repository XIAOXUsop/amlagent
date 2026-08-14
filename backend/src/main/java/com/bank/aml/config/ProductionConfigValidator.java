package com.bank.aml.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生产环境配置校验：仅 prod Profile 激活。
 * 启动时检查密钥长度与默认值，发现演示值直接拒绝启动，避免误部署。
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

    public ProductionConfigValidator(@Value("${aml.security.jwt-secret:}") String jwtSecret,
                                     @Value("${spring.datasource.password:}") String dbPassword,
                                     @Value("${aml.security.cookie-secure:false}") boolean cookieSecure,
                                     @Value("${spring.flyway.enabled:false}") boolean flywayEnabled,
                                     @Value("${spring.jpa.hibernate.ddl-auto:}") String ddlAuto) {
        this.jwtSecret = jwtSecret;
        this.dbPassword = dbPassword;
        this.cookieSecure = cookieSecure;
        this.flywayEnabled = flywayEnabled;
        this.ddlAuto = ddlAuto;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = new java.util.ArrayList<>();
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.contains("change-me") || jwtSecret.length() < 32) {
            violations.add("JWT secret 使用默认值或长度不足（需 ≥32 字符且非 change-me）");
        }
        if (dbPassword == null || dbPassword.isBlank() || dbPassword.contains("aml123456") || dbPassword.contains("root123456")) {
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
        if (!violations.isEmpty()) {
            String message = "生产环境配置校验失败：" + String.join("；", violations);
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("生产环境配置校验通过");
    }
}
