package com.bank.aml.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * 验证 Flyway 迁移产出的 schema 能通过 Hibernate ddl-auto=validate。
 * <p>覆盖任务书 D9 §13.5 的 "Hibernate validate 成功"：生产使用 ddl-auto=validate + Flyway 管理 schema，
 * 若实体与迁移列不一致（如 boolean 用 TINYINT 而非 BIT、缺列等），本测试会在 Spring 上下文启动时失败。
 * 复用本机 Docker 的 MySQL。运行：./mvnw test -Dgroups=integration
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "aml.rag.rerank.enabled=false"
})
class FlywayHibernateValidateTest {

    private static final String SCHEMA = "aml_validate_test";
    private static final String HOST = env("MYSQL_TEST_HOST", "localhost:3307");
    private static final String ROOT_USER = env("MYSQL_ROOT_USER", "root");
    private static final String ROOT_PASSWORD = env("MYSQL_ROOT_PASSWORD", "root123456");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        String serverUrl = "jdbc:mysql://" + HOST
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(serverUrl, ROOT_USER, ROOT_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (Exception e) {
            throw new IllegalStateException("无法创建验证 schema " + SCHEMA, e);
        }
        String schemaUrl = "jdbc:mysql://" + HOST + "/" + SCHEMA
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> ROOT_USER);
        registry.add("spring.datasource.password", () -> ROOT_PASSWORD);
    }

    @Test
    void flywaySchemaPassesHibernateValidation() {
        // 空断言：Flyway 迁移与 Hibernate validate 均发生在 Spring 上下文启动阶段，
        // schema 与实体不一致时启动即失败，本测试随之报错。
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
