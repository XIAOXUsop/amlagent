package com.bank.aml.testinfra;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.sql.DriverManager;
import java.sql.Statement;

/** 为 Spring 集成测试创建独立 MySQL schema，禁止复用本地开发库。 */
public final class IntegrationTestDatabase {

    private static final String HOST = env("MYSQL_TEST_HOST", "localhost:3307");
    private static final String ROOT_USER = env("MYSQL_ROOT_USER", "root");
    private static final String ROOT_PASSWORD = env("MYSQL_ROOT_PASSWORD", "root123456");

    private IntegrationTestDatabase() {
    }

    public static void configure(DynamicPropertyRegistry registry, String schema) {
        if (schema == null || !schema.matches("aml_[a-z0-9_]+_test")) {
            throw new IllegalArgumentException("集成测试 schema 名称不安全：" + schema);
        }
        String serverUrl = "jdbc:mysql://" + HOST
                + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        try (var connection = DriverManager.getConnection(serverUrl, ROOT_USER, ROOT_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (Exception e) {
            throw new IllegalStateException("无法创建隔离集成测试 schema：" + schema, e);
        }
        String schemaUrl = "jdbc:mysql://" + HOST + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        registry.add("spring.datasource.url", () -> schemaUrl);
        registry.add("spring.datasource.username", () -> ROOT_USER);
        registry.add("spring.datasource.password", () -> ROOT_PASSWORD);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
