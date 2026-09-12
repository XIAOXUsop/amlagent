package com.bank.aml.config;

import com.bank.aml.testinfra.TestSqlIdentifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flyway V1→最新版本真实 MySQL 迁移测试（复用本机 Docker 的 MySQL，独立 schema 隔离）。
 * <p>
 * 覆盖任务书 D9 的棕地升级场景：V1 建表 → 插入历史"同一工单多条复核记录"（review_revision 尚未存在， 升级后全部默认 0，会与唯一键冲突）→ V2
 * 迁移回填连续 revision 并创建唯一键。 运行：./mvnw test -Dgroups=integration
 */
@Tag("integration")
class FlywayMigrationTest {

    private static final String SCHEMA = "aml_migration_test";

    private static final String HOST = env("MYSQL_TEST_HOST", "localhost:3307");

    private static final String ROOT_USER = env("MYSQL_ROOT_USER", "root");

    private static final String ROOT_PASSWORD = env("MYSQL_ROOT_PASSWORD", "root123456");

    private static final String JDBC_OPTIONS = "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&useSSL=false&allowPublicKeyRetrieval=true";

    private static final String SERVER_URL = "jdbc:mysql://" + HOST + "/" + JDBC_OPTIONS;

    private static final String SCHEMA_URL = "jdbc:mysql://" + HOST + "/" + SCHEMA + JDBC_OPTIONS;

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
        assertThat(columnExists("aml_case", "review_disposition")).isTrue();
        assertThat(columnExists("aml_case", "review_reason_code")).isTrue();
        assertThat(columnExists("aml_case", "reviewed_at")).isTrue();
        assertThat(columnExists("manual_review", "reason_code")).isTrue();
        assertThat(columnExists("enhanced_due_diligence_request", "required_items_json")).isTrue();
        assertThat(columnExists("enhanced_due_diligence_request", "evidence_references_json")).isTrue();
        assertThat(columnExists("enhanced_due_diligence_request", "assigned_to")).isTrue();
        assertThat(columnExists("enhanced_due_diligence_request", "cancellation_reason")).isTrue();
        assertThat(tableExists("enhanced_due_diligence_evidence")).isTrue();
        assertThat(tableExists("suspicious_transaction_report")).isTrue();
        assertThat(columnExists("audit_log", "event_key")).isTrue();
        assertThat(tableExists("audit_outbox")).isTrue();
        assertThat(columnExists("aml_case", "investigation_contract_version")).isTrue();
        assertThat(tableExists("aml_alert")).isTrue();
        assertThat(tableExists("investigation_hypothesis")).isTrue();
        assertThat(tableExists("investigation_evidence_link")).isTrue();
        assertThat(tableExists("alert_investigation_coverage")).isTrue();
    }

    @Test
    void v35RefundBaselineMigratesWithoutDataLossAndEnforcesV36Constraints() throws Exception {
        recreateSchema();
        migrateTo(MigrationVersion.fromVersion("35"));
        RefundBaseline baseline = insertRefundBaseline();

        migrateTo(null);

        try (Connection connection = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
                PreparedStatement eventCount = connection
                    .prepareStatement("SELECT COUNT(*) FROM refund_event WHERE case_id = ?");
                PreparedStatement allocationCount = connection
                    .prepareStatement("SELECT COUNT(*) FROM refund_allocation WHERE case_id = ?")) {
            eventCount.setLong(1, baseline.caseId());
            allocationCount.setLong(1, baseline.caseId());
            try (ResultSet events = eventCount.executeQuery(); ResultSet allocations = allocationCount.executeQuery()) {
                events.next();
                allocations.next();
                assertThat(events.getInt(1)).isEqualTo(2);
                assertThat(allocations.getInt(1)).isEqualTo(1);
            }
        }

        assertThatThrownBy(() -> setAllocationAmountToZero(baseline.allocationId())).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> setMissingReversalReference(baseline.originalEventId()))
            .isInstanceOf(SQLException.class);
    }

    @Test
    void v36StopsBeforeConstraintDdlWhenRefundBaselineIsInconsistent() throws Exception {
        recreateSchema();
        migrateTo(MigrationVersion.fromVersion("35"));
        RefundBaseline baseline = insertRefundBaseline();
        setAllocationCurrency(baseline.allocationId(), "USD");

        assertThatThrownBy(() -> migrateTo(null)).isInstanceOf(FlywayException.class);
        assertThat(uniqueKeyExists("refund_event", "uk_refund_event_identity")).isFalse();
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
        String quotedSchema = TestSqlIdentifier.mysqlSchema(SCHEMA);
        try (Connection conn = DriverManager.getConnection(SERVER_URL, ROOT_USER, ROOT_PASSWORD);
                Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + quotedSchema);
            st.execute("CREATE DATABASE " + quotedSchema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private long insertHistoricalCaseAndReviews() throws Exception {
        try (Connection conn = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD)) {
            long caseId;
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(
                        "INSERT INTO aml_case (customer_id, customer_name, alert_rule, status, created_at, updated_at) "
                                + "VALUES ('C001', '张伟', '历史工单', 'HOLD', NOW(6), NOW(6))",
                        Statement.RETURN_GENERATED_KEYS);
                try (ResultSet keys = st.getGeneratedKeys()) {
                    keys.next();
                    caseId = keys.getLong(1);
                }
            }
            // V1 阶段 manual_review 无 review_revision 列，只写旧列
            try (Statement st = conn.createStatement()) {
                st.executeUpdate(
                        "INSERT INTO manual_review (case_id, reviewer_id, decision, comment, created_at) VALUES " + "("
                                + caseId + ", 'reviewer', 'APPROVE', '历史复核1', '2026-01-01 10:00:00'), " + "(" + caseId
                                + ", 'reviewer', 'ESCALATE', '历史复核2', '2026-01-02 10:00:00'), " + "(" + caseId
                                + ", 'reviewer', 'APPROVE', '历史复核3', '2026-01-03 10:00:00')");
            }
            return caseId;
        }
    }

    private RefundBaseline insertRefundBaseline() throws Exception {
        try (Connection connection = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD)) {
            connection.setAutoCommit(false);
            try {
                long caseId = insertCase(connection, "C-REFUND-BASELINE");
                long originalId = insertRefundEvent(connection, caseId, "BASELINE-POSTED", "POSTED", null);
                long reversalId = insertRefundEvent(connection, caseId, "BASELINE-REVERSAL", "REVERSED", originalId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE refund_event SET event_status = 'REVERSED', reversed_event_id = ? WHERE id = ?")) {
                    statement.setLong(1, reversalId);
                    statement.setLong(2, originalId);
                    statement.executeUpdate();
                }
                long allocationId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO refund_allocation (case_id, refund_event_id, original_transaction_id, "
                                + "original_allocation_key, allocated_amount, currency, created_by, created_at) "
                                + "VALUES (?, ?, 'TX-BASELINE', 'ALLOC-BASELINE', 20.00, 'CNY', 'test', NOW(6))",
                        Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, caseId);
                    statement.setLong(2, originalId);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        allocationId = keys.getLong(1);
                    }
                }
                connection.commit();
                return new RefundBaseline(caseId, originalId, allocationId);
            }
            catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private long insertCase(Connection connection, String customerId) throws Exception {
        try (PreparedStatement statement = connection
            .prepareStatement(
                    "INSERT INTO aml_case (customer_id, customer_name, alert_rule, status, created_at, updated_at) "
                            + "VALUES (?, '迁移基线客户', '退款迁移验证', 'HOLD', NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, customerId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertRefundEvent(Connection connection, long caseId, String externalId, String status,
            Long reversedEventId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO refund_event (case_id, source_system, external_event_id, event_status, payer_subject, "
                        + "payee_subject, amount, currency, effective_at, recorded_at, payload_digest, "
                        + "reversed_event_id, created_by, created_at) VALUES (?, 'CORE_BANKING', ?, ?, '付款方', "
                        + "'收款方', 20.00, 'CNY', NOW(6), NOW(6), ?, ?, 'test', NOW(6))",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, caseId);
            statement.setString(2, externalId);
            statement.setString(3, status);
            statement.setString(4, "0".repeat(64));
            if (reversedEventId == null) {
                statement.setNull(5, Types.BIGINT);
            }
            else {
                statement.setLong(5, reversedEventId);
            }
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void setAllocationAmountToZero(long allocationId) throws Exception {
        try (Connection connection = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
                PreparedStatement statement = connection
                    .prepareStatement("UPDATE refund_allocation SET allocated_amount = 0 WHERE id = ?")) {
            statement.setLong(1, allocationId);
            statement.executeUpdate();
        }
    }

    private void setMissingReversalReference(long originalEventId) throws Exception {
        try (Connection connection = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
                PreparedStatement statement = connection
                    .prepareStatement("UPDATE refund_event SET reversed_event_id = ? WHERE id = ?")) {
            statement.setLong(1, Long.MAX_VALUE);
            statement.setLong(2, originalEventId);
            statement.executeUpdate();
        }
    }

    private void setAllocationCurrency(long allocationId, String currency) throws Exception {
        try (Connection connection = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
                PreparedStatement statement = connection
                    .prepareStatement("UPDATE refund_allocation SET currency = ? WHERE id = ?")) {
            statement.setString(1, currency);
            statement.setLong(2, allocationId);
            statement.executeUpdate();
        }
    }

    private record RefundBaseline(long caseId, long originalEventId, long allocationId) {
    }

    private List<Integer> queryReviewRevisions(long caseId) throws Exception {
        List<Integer> revisions = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
                PreparedStatement statement = conn.prepareStatement(
                        "SELECT review_revision FROM manual_review WHERE case_id = ? ORDER BY created_at, id")) {
            statement.setLong(1, caseId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    revisions.add(rs.getInt(1));
                }
            }
        }
        return revisions;
    }

    private boolean uniqueKeyExists(String table, String keyName) throws Exception {
        try (Connection conn = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
                PreparedStatement statement = conn
                    .prepareStatement("SELECT COUNT(*) FROM information_schema.statistics "
                            + "WHERE table_schema = ? AND table_name = ? AND index_name = ?")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, keyName);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        try (Connection conn = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
                PreparedStatement statement = conn.prepareStatement("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (Connection conn = DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
                PreparedStatement statement = conn.prepareStatement("SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

}
