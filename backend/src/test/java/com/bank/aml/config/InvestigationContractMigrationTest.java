package com.bank.aml.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2/V2-08~11（A3-01 修正后）：调查契约 V25→V26 真实 MySQL 迁移与两阶段影响查询实跑测试。
 *
 * <p>要点（对应验收报告 A3-01 修正要求）：
 * <ul>
 *   <li>V25 阶段插入的语句不含 V26 新列 {@code hypothesis_revision}；</li>
 *   <li>已报送报告先创建 {@code manual_review} 再用其真实 ID 满足 V23 外键；</li>
 *   <li>{@code queryLong} 检查 {@code wasNull()}，区分 SQL NULL 与 0；</li>
 *   <li>按列名（而非列序号）读取外部预警编号等列；</li>
 *   <li>“健康对照”的版本绑定在迁移完成后通过显式操作建立（模拟分析员重新确认），
 *       迁移前保持 NULL，与“V26 不回填”政策一致；</li>
 *   <li>V25/V26 文件的每一条 SELECT 都必须执行；归档查询（V26 第 6 条）有夹具与明确断言。</li>
 * </ul>
 *
 * <p>运行：{@code mvnw test -Dgroups=integration}（需要 MYSQL_TEST_HOST 指向测试实例，默认 localhost:3307；
 * 本测试 DROP/CREATE 专属 schema {@code aml_contract_migration_test}，不得指向业务库）。
 */
@Tag("integration")
class InvestigationContractMigrationTest {

    private static final String SCHEMA = "aml_contract_migration_test";
    private static final String HOST = env("MYSQL_TEST_HOST", "localhost:3307");
    private static final String ROOT_USER = env("MYSQL_ROOT_USER", "root");
    private static final String ROOT_PASSWORD = env("MYSQL_ROOT_PASSWORD", "root123456");
    private static final int CAPACITY = 8; // 与部署默认 aml.agent.max-linked-alerts 一致

    private static final String SERVER_URL = "jdbc:mysql://" + HOST
            + "/?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    private static final String SCHEMA_URL = "jdbc:mysql://" + HOST + "/" + SCHEMA
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";

    /** 影响查询文件目录：默认直接读取仓库内 docs/ops，避免副本漂移；可用环境变量覆盖。 */
    private static final String OPS_DIR = env("IMPACT_SQL_DIR", "../docs/ops");

    @Test
    void migrationPreservesFactsAndImpactQueriesHitExactFixtures() throws Exception {
        recreateSchema();

        // ---- 阶段一：仅迁移到 V25，插入合成夹具（V25 语句不含 V26 新列） ----
        migrateTo(MigrationVersion.fromVersion("25"));
        V25Fixtures v25 = insertV25Fixtures();

        List<String> v25Statements = loadStatements("investigation-impact-queries-v25.sql");
        assertThat(v25Statements).hasSize(5);

        // V2-08：V25 阶段全部查询可执行（V25 schema 无 hypothesis_revision 列，查询不得引用 V26 列）
        Set<Long> autoDoneNoReview = queryCaseIds(v25Statements.get(0));
        assertThat(autoDoneNoReview).contains(v25.doneWithoutReview)
                .doesNotContain(v25.doneWithReview, v25.legacyDoneNoReview);

        Set<Long> eddBlocked = queryCaseIds(v25Statements.get(1));
        assertThat(eddBlocked).contains(v25.eddOnTerminal).doesNotContain(v25.eddOnEditable);

        Set<Long> contradictions = queryCaseIds(v25Statements.get(2));
        assertThat(contradictions).contains(v25.contradictory).doesNotContain(v25.consistent);

        Set<Long> submitted = queryCaseIds(v25Statements.get(3));
        assertThat(submitted).contains(v25.submittedReport);

        Set<Long> overCap = queryCaseIds(v25Statements.get(4));
        assertThat(overCap).contains(v25.overCapacity).doesNotContain(v25.atCapacity, v25.failedOverCapacity);

        // ---- 阶段二：应用 V26，验证存量事实保留、新列/索引存在 ----
        migrateTo(null);

        assertThat(columnExists("alert_investigation_coverage", "hypothesis_revision")).isTrue();
        assertThat(columnExists("investigation_snapshot", "alerts_digest")).isTrue();
        assertThat(indexExists("alert_investigation_coverage", "idx_coverage_case_hypothesis")).isTrue();

        // V2-09：迁移不猜测回填历史绑定（NULL 保持 NULL，不得被当成 0）、不改写既有事实
        assertThat(queryLong("SELECT hypothesis_revision FROM alert_investigation_coverage WHERE case_id = "
                + v25.legacyNullBinding)).isNull();
        assertThat(queryLong("SELECT hypothesis_revision FROM alert_investigation_coverage WHERE case_id = "
                + v25.contradictory)).isNull();
        assertThat(queryString("SELECT conclusion FROM alert_investigation_coverage WHERE case_id = "
                + v25.legacyNullBinding)).isEqualTo("SUSPICIOUS");
        assertThat(queryString("SELECT status FROM aml_case WHERE id = " + v25.doneWithoutReview)).isEqualTo("DONE");
        assertThat(queryLong("SELECT reviewed_at IS NULL FROM aml_case WHERE id = " + v25.doneWithoutReview))
                .isEqualTo(1L);

        // 迁移后通过显式操作建立健康绑定（模拟分析员对健康对照执行“重新确认”）；
        // 存量 NULL 绑定保持原样等待人工处理，不批量回填。
        bindHypothesisRevision(v25.consistentCoverageId, 1L);

        // ---- V26 阶段夹具：新鲜绑定 / 过期绑定 / 归档快照 ----
        V26Fixtures v26 = insertV26Fixtures();

        List<String> v26Statements = loadStatements("investigation-impact-queries-v26.sql");
        assertThat(v26Statements).hasSize(6);

        // V2-10：V26 阶段全部 6 条查询都必须执行；逐条断言命中/不命中
        Set<Long> nullBindings = queryCaseIds(v26Statements.get(0));
        assertThat(nullBindings).contains(v25.legacyNullBinding, v25.contradictory)
                .doesNotContain(v25.consistent, v25.doneWithReview);

        Set<String> staleExternalIds = queryColumnSet(v26Statements.get(1), "external_alert_id");
        assertThat(staleExternalIds).contains("ALERT-STALE-BINDING").doesNotContain("ALERT-FRESH-BINDING");

        Set<Long> v26Contradictions = queryCaseIds(v26Statements.get(2));
        assertThat(v26Contradictions).contains(v25.contradictory).doesNotContain(v25.consistent);

        Set<Long> unfinished = queryCaseIds(v26Statements.get(3));
        assertThat(unfinished).contains(v25.overCapacity, v25.legacyNullBinding, v25.eddOnEditable)
                .doesNotContain(v25.doneWithReview, v25.submittedReport);

        Set<Long> doneNoReview = queryCaseIds(v26Statements.get(4));
        assertThat(doneNoReview).contains(v25.doneWithoutReview).doesNotContain(v25.doneWithReview);

        // V26 第 6 条：归档快照摘要清单（含迁移前存量 NULL 与迁移后新归档两类）
        Set<String> snapshotIds = queryColumnSet(v26Statements.get(5), "snapshot_id");
        assertThat(snapshotIds).contains(v25.legacySnapshotId, v26.newSnapshotId);

        // V2-11 附加：只读重跑结果一致（查询确定性）
        assertThat(queryCaseIds(v25Statements.get(0))).isEqualTo(autoDoneNoReview);
        assertThat(queryColumnSet(v26Statements.get(1), "external_alert_id")).isEqualTo(staleExternalIds);

        // V2-09 附加：迁移前写入的存量归档在升级后保留原貌（主键/来源摘要/载荷逐项核对），
        // 新摘要列保持 NULL，不得被迁移伪造；新归档摘要非空。
        assertThat(queryString("SELECT alerts_digest FROM investigation_snapshot WHERE snapshot_id = '"
                + v26.newSnapshotId + "'")).isEqualTo(v26.newDigest);
        assertThat(queryString("SELECT alerts_digest FROM investigation_snapshot WHERE snapshot_id = '"
                + v25.legacySnapshotId + "'")).isNull();
        assertThat(queryString("SELECT source_digest FROM investigation_snapshot WHERE snapshot_id = '"
                + v25.legacySnapshotId + "'")).isEqualTo(v25.legacySourceDigest);
        assertThat(queryString("SELECT payload_ciphertext FROM investigation_snapshot WHERE snapshot_id = '"
                + v25.legacySnapshotId + "'")).isEqualTo(v25.legacyPayload);
        assertThat(queryColumnSet("SELECT snapshot_id, alerts_digest FROM investigation_snapshot "
                + "WHERE snapshot_id = '" + v25.legacySnapshotId + "'", "snapshot_id"))
                .containsExactly(v25.legacySnapshotId);
    }

    // ---- 夹具构造 ----

    /** V25 阶段夹具的已解析主键。 */
    private record V25Fixtures(long doneWithoutReview, long doneWithReview, long legacyDoneNoReview,
                               long eddOnTerminal, long eddOnEditable, long contradictory, long consistent,
                               long submittedReport, long overCapacity, long atCapacity,
                               long failedOverCapacity, long legacyNullBinding, long consistentCoverageId,
                               String legacySnapshotId, String legacySourceDigest, String legacyPayload) {
    }

    private record V26Fixtures(String newSnapshotId, String newDigest) {
    }

    private V25Fixtures insertV25Fixtures() throws Exception {
        long doneWithoutReview = insertCase("C-DONE-NOREVIEW", "DONE", 1, null, null);
        long doneWithReview = insertCase("C-DONE-REVIEWED", "DONE", 1, "EXCLUDE_FALSE_POSITIVE", "OK");
        long legacyDoneNoReview = insertCase("C-LEGACY-DONE", "DONE", 0, null, null);
        long eddOnTerminal = insertCase("C-EDD-TERMINAL", "REPORT_PENDING", 1, "CONFIRM_SUSPICIOUS", null);
        long eddOnEditable = insertCase("C-EDD-PENDING", "PENDING", 1, null, null);
        long contradictory = insertCase("C-CONTRADICTION", "HOLD", 1, null, null);
        long consistent = insertCase("C-CONSISTENT", "HOLD", 1, null, null);
        long submittedReport = insertCase("C-SUBMITTED", "DONE", 1, "CONFIRM_SUSPICIOUS", null);
        long overCapacity = insertCase("C-OVER-CAP", "HOLD", 1, null, null);
        long atCapacity = insertCase("C-AT-CAP", "HOLD", 1, null, null);
        long failedOverCapacity = insertCase("C-FAILED-CAP", "FAILED", 1, null, null);
        long legacyNullBinding = insertCase("C-NULL-BINDING", "HOLD", 1, null, null);

        insertOpenEdd(eddOnTerminal, 1);
        insertOpenEdd(eddOnEditable, 1);

        // contradictory：假设已排除（rev 2），覆盖仍为可疑 → 矛盾；迁移前覆盖绑定保持 NULL
        long hypRejected = insertHypothesis(contradictory, "REJECTED", 2);
        insertAlert(contradictory, "ALERT-CONTRADICTION", "LINKED");
        long contradictionCoverage = insertCoverageV25(contradictory,
                alertId(contradictory, "ALERT-CONTRADICTION"), hypRejected, "SUSPICIOUS");

        // consistent：假设已确认（rev 1），覆盖可疑；V25 阶段插入不含绑定列（NULL，待迁移后人工补齐）
        long hypConfirmed = insertHypothesis(consistent, "CONFIRMED", 1);
        insertAlert(consistent, "ALERT-CONSISTENT", "LINKED");
        long consistentCoverage = insertCoverageV25(consistent,
                alertId(consistent, "ALERT-CONSISTENT"), hypConfirmed, "SUSPICIOUS");

        // 已报送报告：先创建有效人工复核，再以其真实 ID 满足 V23 外键 fk_str_review
        long submittedReviewId = insertManualReview(submittedReport, "CONFIRM_SUSPICIOUS");
        insertSubmittedReport(submittedReport, submittedReviewId);

        // 容量夹具：9 / 8 / 9 条 LINKED 预警
        for (int i = 0; i < 9; i++) insertAlert(overCapacity, "ALERT-OVER-" + i, "LINKED");
        for (int i = 0; i < 8; i++) insertAlert(atCapacity, "ALERT-AT-" + i, "LINKED");
        for (int i = 0; i < 9; i++) insertAlert(failedOverCapacity, "ALERT-FAILED-" + i, "LINKED");

        // NULL 绑定存量形态：已确认假设 + 可疑覆盖（V26 迁移后 hypothesis_revision 为 NULL）
        long nullBindingHyp = insertHypothesis(legacyNullBinding, "CONFIRMED", 3);
        insertAlert(legacyNullBinding, "ALERT-NULL-BINDING", "LINKED");
        insertCoverageV25(legacyNullBinding, alertId(legacyNullBinding, "ALERT-NULL-BINDING"),
                nullBindingHyp, "SUSPICIOUS");

        // 存量归档：V25 阶段（迁移前）写入，INSERT 不含 alerts_digest 列（该列由 V26 新增）；
        // 升级后断言原貌（主键/来源摘要/载荷）保留且新摘要列为 NULL。
        long snapshotCase = insertCase("C-SNAPSHOT-LEGACY", "HOLD", 1, null, null);
        String legacySnapshotId = "case-" + snapshotCase + "-v0-legacy";
        String legacySourceDigest = sha256Hex("w2-legacy-source");
        String legacyPayload = "legacy-payload-before-migration";
        insertSnapshotV25(legacySnapshotId, snapshotCase, 1, legacySourceDigest, legacyPayload);

        return new V25Fixtures(doneWithoutReview, doneWithReview, legacyDoneNoReview,
                eddOnTerminal, eddOnEditable, contradictory, consistent,
                submittedReport, overCapacity, atCapacity, failedOverCapacity,
                legacyNullBinding, consistentCoverage,
                legacySnapshotId, legacySourceDigest, legacyPayload);
    }

    private V26Fixtures insertV26Fixtures() throws Exception {
        long freshCase = insertCase("C-FRESH-BINDING", "HOLD", 1, null, null);
        long freshHyp = insertHypothesis(freshCase, "CONFIRMED", 3);
        insertAlert(freshCase, "ALERT-FRESH-BINDING", "LINKED");
        // 新建案件由应用写入当前绑定（模拟 v1 正常流程提交），非迁移回填
        long freshCoverage = insertCoverageV25(freshCase, alertId(freshCase, "ALERT-FRESH-BINDING"),
                freshHyp, "SUSPICIOUS");
        bindHypothesisRevision(freshCoverage, 3L);

        long staleCase = insertCase("C-STALE-BINDING", "HOLD", 1, null, null);
        long staleHyp = insertHypothesis(staleCase, "CONFIRMED", 3);
        insertAlert(staleCase, "ALERT-STALE-BINDING", "LINKED");
        long staleCoverage = insertCoverageV25(staleCase, alertId(staleCase, "ALERT-STALE-BINDING"),
                staleHyp, "SUSPICIOUS");
        // 过期绑定：早先绑定的版本 2，之后假设已推进到 3
        bindHypothesisRevision(staleCoverage, 2L);

        // 新归档：V26 迁移后由新代码写入，带 alerts_digest（存量 NULL 与新摘要并存，供第 6 条查询断言）
        String newDigest = sha256Hex("w2-frozen-alerts");
        String newSnapshotId = "case-" + freshCase + "-v1-digested";
        insertSnapshotV26(newSnapshotId, freshCase, 2, newDigest);
        return new V26Fixtures(newSnapshotId, newDigest);
    }

    private long insertCase(String customerId, String status, int contractVersion,
                            String disposition, String reasonCode) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO aml_case (customer_id, customer_name, alert_rule, status, risk_level,"
                        + " raw_risk_level, investigation_contract_version, review_disposition,"
                        + " review_reason_code, reviewed_at, created_at, updated_at)"
                        + " VALUES (?, '合成客户', 'W2 夹具', ?, '高风险', '高风险', ?, ?, ?,"
                        + " ?, NOW(6), NOW(6))", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, customerId);
            ps.setString(2, status);
            ps.setInt(3, contractVersion);
            ps.setString(4, disposition);
            ps.setString(5, reasonCode);
            ps.setObject(6, "EXCLUDE_FALSE_POSITIVE".equals(disposition)
                    ? java.sql.Timestamp.valueOf("2026-01-01 10:00:00") : null);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertOpenEdd(long caseId, int round) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO enhanced_due_diligence_request (case_id, round_no, reason_code,"
                        + " required_items_json, requested_by, requested_at, due_at, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'SOURCE_OF_FUNDS_UNCLEAR', '[\"资金来源说明\"]', 'reviewer',"
                        + " NOW(6), DATE_ADD(NOW(6), INTERVAL 7 DAY), 'OPEN', NOW(6), NOW(6))")) {
            ps.setLong(1, caseId);
            ps.setInt(2, round);
            ps.executeUpdate();
        }
    }

    private long insertHypothesis(long caseId, String status, int revision) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO investigation_hypothesis (case_id, scenario_code, hypothesis_code, title,"
                        + " investigation_question, required_evidence_types, status, rationale, revision,"
                        + " created_by, created_at, updated_at)"
                        + " VALUES (?, 'STRUCTURING', ?, '合成假设', '是否成立？', 'TRANSACTION', ?,"
                        + " '合成判断依据', ?, 'fixture', NOW(6), NOW(6))", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, caseId);
            ps.setString(2, "H-" + caseId);
            ps.setString(3, status);
            ps.setInt(4, revision);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertAlert(long caseId, String externalId, String status) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO aml_alert (external_alert_id, customer_id, rule_code, scenario_code,"
                        + " hit_reason, occurred_at, status, case_id, revision, created_by, created_at, updated_at)"
                        + " VALUES (?, ?, 'RULE-W2', 'STRUCTURING', '合成命中', NOW(6), ?, ?, 0, 'fixture',"
                        + " NOW(6), NOW(6))")) {
            ps.setString(1, externalId);
            ps.setString(2, "C-" + caseId);
            ps.setString(3, status);
            ps.setLong(4, caseId);
            ps.executeUpdate();
        }
    }

    private long alertId(long caseId, String externalId) throws Exception {
        return queryLong("SELECT id FROM aml_alert WHERE case_id = " + caseId
                + " AND external_alert_id = '" + externalId + "'");
    }

    /**
     * V25 阶段覆盖插入：INSERT 列清单只含 V25 已存在列（无 hypothesis_revision）。
     * 迁移后该行为 NULL，由“重新确认”类显式操作补齐。
     */
    private long insertCoverageV25(long caseId, long alertId, long hypothesisId,
                                   String conclusion) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO alert_investigation_coverage (alert_id, case_id, hypothesis_id,"
                        + " conclusion, analysis_summary, revision, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, '合成覆盖分析', 0, NOW(6), NOW(6))",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, alertId);
            ps.setLong(2, caseId);
            ps.setLong(3, hypothesisId);
            ps.setString(4, conclusion);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** 模拟分析员“重新确认/正常提交”后的显式版本绑定（非迁移回填）。 */
    private void bindHypothesisRevision(long coverageId, long revision) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE alert_investigation_coverage SET hypothesis_revision = ? WHERE id = ?")) {
            ps.setLong(1, revision);
            ps.setLong(2, coverageId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private long insertManualReview(long caseId, String decision) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO manual_review (case_id, reviewer_id, decision, comment, created_at,"
                        + " completed_at) VALUES (?, 'reviewer', ?, 'W2 夹具复核', NOW(6), NOW(6))",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, caseId);
            ps.setString(2, decision);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertSubmittedReport(long caseId, long reviewId) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO suspicious_transaction_report (case_id, review_id, status, report_reason,"
                        + " created_by, revision, external_reference, submitted_by, submitted_at,"
                        + " created_at, updated_at)"
                        + " VALUES (?, ?, 'SUBMITTED', '合成报送理由', 'fixture', 0, 'EXT-W2-001',"
                        + " 'reviewer', NOW(6), NOW(6), NOW(6))")) {
            ps.setLong(1, caseId);
            ps.setLong(2, reviewId);
            ps.executeUpdate();
        }
    }

    /** V25 阶段归档插入：列清单只含 V25 已存在列（无 alerts_digest，与生产写入路径一致）。 */
    private void insertSnapshotV25(String snapshotId, long caseId, int executionVersion,
                                   String sourceDigest, String payload) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO investigation_snapshot (snapshot_id, case_id, execution_version, as_of_time,"
                        + " source_system, source_version, legal_index_version, source_digest,"
                        + " payload_ciphertext, created_at)"
                        + " VALUES (?, ?, ?, NOW(6), 'FIXTURE', 'v1', 'legal-v1', ?, ?, NOW(6))")) {
            ps.setString(1, snapshotId);
            ps.setLong(2, caseId);
            ps.setInt(3, executionVersion);
            ps.setString(4, sourceDigest);
            ps.setString(5, payload);
            ps.executeUpdate();
        }
    }

    /** V26 迁移后的新归档插入：携带 alerts_digest（模拟新代码正常归档）。 */
    private void insertSnapshotV26(String snapshotId, long caseId, int executionVersion,
                                   String alertsDigest) throws Exception {
        try (Connection conn = connection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO investigation_snapshot (snapshot_id, case_id, execution_version, as_of_time,"
                        + " source_system, source_version, legal_index_version, source_digest,"
                        + " payload_ciphertext, alerts_digest, created_at)"
                        + " VALUES (?, ?, ?, NOW(6), 'FIXTURE', 'v1', 'legal-v1',"
                        + " SHA2('fixture', 256), 'fixture-payload', ?, NOW(6))")) {
            ps.setString(1, snapshotId);
            ps.setLong(2, caseId);
            ps.setInt(3, executionVersion);
            ps.setString(4, alertsDigest);
            ps.executeUpdate();
        }
    }

    // ---- SQL 文件加载与执行 ----

    /** 读取仓库内 docs/ops 的影响查询文件（交付即测试对象，避免副本漂移）。 */
    private List<String> loadStatements(String fileName) throws Exception {
        Path path = Path.of(OPS_DIR, fileName);
        assertThat(Files.exists(path)).as("影响查询文件必须存在：" + path).isTrue();
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        StringBuilder cleaned = new StringBuilder();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            String statement = part.trim();
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
        }
        for (String statement : statements) {
            assertThat(statement.toUpperCase()).startsWith("SELECT");
        }
        return statements;
    }

    /** 按第一列（案件/记录 ID）读取查询结果集合。 */
    private Set<Long> queryCaseIds(String sql) throws Exception {
        Set<Long> ids = new HashSet<>();
        try (Connection conn = connection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    /** 按列名读取查询结果集合（外部预警编号等列可能不在第 1 列）。 */
    private Set<String> queryColumnSet(String sql, String columnLabel) throws Exception {
        Set<String> values = new HashSet<>();
        try (Connection conn = connection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(columnLabel));
            }
        }
        return values;
    }

    /** 可空单值读取：显式区分 SQL NULL 与 0。 */
    private Long queryLong(String sql) throws Exception {
        try (Connection conn = connection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            long value = rs.getLong(1);
            return rs.wasNull() ? null : value;
        }
    }

    private String queryString(String sql) throws Exception {
        try (Connection conn = connection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            String value = rs.getString(1);
            return rs.wasNull() ? null : value;
        }
    }

    // ---- 连接与迁移辅助（与 FlywayMigrationTest 同一套环境约定） ----

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(SCHEMA_URL, ROOT_USER, ROOT_PASSWORD);
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

    private boolean columnExists(String table, String column) throws Exception {
        try (Connection conn = connection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                             + "' AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private boolean indexExists(String table, String indexName) throws Exception {
        try (Connection conn = connection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = '" + SCHEMA
                             + "' AND table_name = '" + table + "' AND index_name = '" + indexName + "'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
