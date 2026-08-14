package com.bank.aml.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V1→V2 真实 MySQL 迁移测试（复用本机 Docker 的 MySQL，独立 schema 隔离）。
 * <p>覆盖任务书 D9 的棕地升级场景：V1 建表 → 插入历史"同一工单多条复核记录"（review_revision 尚未存在，
 * 升级后全部默认 0，会与唯一键冲突）→ V2 迁移回填连续 revision 并创建唯一键。
 * 运行：./mvnw test -Dgroups=integration
 */
@Tag("integration")
class FlywayMigrationTest {

    private static final String SCHEMA = "aml_migration_test";
    private static final String HOST = env("MYSQL_TEST_HOST", "localhost:3307");
    private static final String ROOT_USER = env("MYSQL_ROOT_USER", "root");
    private static final String ROOT_PASSWORD = env("MYSQL_ROOT_PASSWORD", "root123456");

    private static final String SERVER_URL = "jdbc:mysql://" + HOST
            + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String SCHEMA_URL = "jdbc:mysql://" + HOST + "/" + SCHEMA
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";

    @Test
    void v1ToV2BackfillsHistoricalReviewRevisions() throws Exception {
        recreateSchema();

        // 1. 先迁移到 V1（旧基线，manual_review 尚无 review_revision）
        migrateTo(MigrationVersion.fromVersion("1"));

        // 2. 插入历史数据：同一工单的 3 条复核记录（时间有序）
        long caseId = insertHistoricalCaseAndReviews();

        // 3. 迁移到最新（V2）：回填 review_revision 并创建唯一键
        migrateTo(null);

        // 4. 断言：历史记录被回填为连续的 0/1/2，唯一键存在，模型溯源列已补齐
        List<Integer> revisions = queryReviewRevisions(caseId);
        assertThat(revisions).containsExactly(0, 1, 2);

        assertThat(uniqueKeyExists("manual_review", "uk_review_case_revision")).isTrue();
        assertThat(columnExists("aml_case", "review_revision")).isTrue();
        assertThat(columnExists("aml_case", "model_provider")).isTrue();
        assertThat(columnExists("aml_case", "model_name")).isTrue();
        assertThat(columnExists("aml_case", "model_fallback")).isTrue();
    }

    private void migrateTo(MigrationVersion target) {
        FluentConfiguration config = Flyway.configure()
                .dataSource(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    private void recreateSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(SERVER_URL, ROOT_USER, ROOT_PASSWORD);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            st.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private long insertHistoricalCaseAndReviews() throws Exception {
        try (Connection conn = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD)) {
            long caseId;
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO aml_case (customer_id, customer_name, alert_rule, status, created_at, updated_at) "
                                + "VALUES ('C001', '张伟', '历史工单', 'HOLD', NOW(6), NOW(6))",
                        Statement.RETURN_GENERATED_KEYS);
                try (ResultSet keys = st.getGeneratedKeys()) {
                    keys.next();
                    caseId = keys.getLong(1);
                }
            }
            // V1 阶段 manual_review 无 review_revision 列，只写旧列
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO manual_review (case_id, reviewer_id, decision, comment, created_at) VALUES "
                        + "(" + caseId + ", 'reviewer', 'APPROVE', '历史复核1', '2026-01-01 10:00:00'), "
                        + "(" + caseId + ", 'reviewer', 'ESCALATE', '历史复核2', '2026-01-02 10:00:00'), "
                        + "(" + caseId + ", 'reviewer', 'APPROVE', '历史复核3', '2026-01-03 10:00:00')");
            }
            return caseId;
        }
    }

    private List<Integer> queryReviewRevisions(long caseId) throws Exception {
        List<Integer> revisions = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT review_revision FROM manual_review WHERE case_id = " + caseId
                             + " ORDER BY created_at, id")) {
            while (rs.next()) {
                revisions.add(rs.getInt(1));
            }
        }
        return revisions;
    }

    private boolean uniqueKeyExists(String table, String keyName) throws Exception {
        try (Connection conn = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.statistics "
                             + "WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + table
                             + "' AND index_name = '" + keyName + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        try (Connection conn = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.columns "
                             + "WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + table
                             + "' AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
