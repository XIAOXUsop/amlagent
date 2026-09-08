package com.bank.aml.explanation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.investigation.AlertInvestigationCoverage;
import com.bank.aml.investigation.AlertInvestigationCoverageRepository;
import com.bank.aml.investigation.AlertCoverageConclusion;
import com.bank.aml.investigation.AlertStatus;
import com.bank.aml.investigation.AmlAlert;
import com.bank.aml.investigation.AmlAlertRepository;
import com.bank.aml.investigation.HypothesisStatus;
import com.bank.aml.investigation.InvestigationHypothesis;
import com.bank.aml.investigation.InvestigationHypothesisRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A6 修复的真实数据库验收（RC-02 行级 / TP-27 真实 epoch 幂等 / RC-03/05 事务语义）。
 * <p>独立 schema（aml_explanation_a6_test），V1~V31 全链迁移后用真实 Repository/Service 执行；
 * 与本地开发库和其它集成测试隔离。
 * 运行：./mvnw -Pintegration-test test -Dtest=ExplanationV3DatabaseIntegrationTest
 */
@Tag("integration")
@SpringBootTest
class ExplanationV3DatabaseIntegrationTest {

    private static final String SCHEMA = "aml_explanation_a6_test";
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
            throw new IllegalStateException("无法创建隔离 schema " + SCHEMA, e);
        }
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + HOST + "/" + SCHEMA
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", () -> ROOT_USER);
        registry.add("spring.datasource.password", () -> ROOT_PASSWORD);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("aml.rag.rerank.enabled", () -> "false");
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @Autowired
    private ExplanationWorkspaceService service;
    @Autowired
    private CaseRepository caseRepository;
    @Autowired
    private AmlAlertRepository alertRepository;
    @Autowired
    private AlertInvestigationCoverageRepository coverageRepository;
    @Autowired
    private InvestigationHypothesisRepository hypothesisRepository;
    @Autowired
    private EvidenceArtifactVersionRepository artifactRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DemoEvidenceSourceAdapter evidenceSource;
    @Autowired
    private com.bank.aml.datasource.CustomerDataPort customerDataPort;

    // ==================== RC-02（A6-06）：未取得内容行级落库 + CHECK 生效 ====================

    /** UNAVAILABLE 材料行：content_sha256=NULL 真实落库；RESOLVED 行必有摘要（CHECK 约束）。 */
    @Test
    void unavailableArtifactPersistsWithoutDigestAndResolvedRequiresDigest() {
        Long caseId = createV2CaseWithLinkedAlert("RC02");

        // 来源不可用：UNAVAILABLE 行落库，无摘要
        evidenceSource.markUnavailable("CORE_BANKING", "RC02-MISSING");
        ExplanationViews.EvidenceView view = service.captureEvidence(caseId, "CORE_BANKING",
                "RC02-MISSING", "analyst");
        assertThat(view.availability()).isEqualTo("UNAVAILABLE");

        // 行级断言（不信任 DTO）：content_sha256 IS NULL + availability=UNAVAILABLE
        EvidenceArtifactVersion stored = artifactRepository
                .findTopByCaseIdAndArtifactKeyOrderByVersionDesc(caseId, "core_banking:rc02-missing")
                .orElseThrow();
        assertThat(stored.getContentSha256()).isNull();
        assertThat(stored.getAvailability()).isEqualTo("UNAVAILABLE");

        // 来源恢复 → v2 追加：RESOLVED 行有服务器摘要；v1 UNAVAILABLE 保留
        evidenceSource.putFixture("CORE_BANKING", "RC02-MISSING", "恢复后的来源内容");
        ExplanationViews.EvidenceView v2 = service.captureEvidence(caseId, "CORE_BANKING",
                "RC02-MISSING", "analyst");
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.availability()).isEqualTo("RESOLVED");
        assertThat(v2.contentSha256()).isEqualTo(sha256("恢复后的来源内容"));

        // CHECK 生效：直接写 RESOLVED + NULL 摘要 → 数据库拒绝
        assertThatThrownBy(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl(), ROOT_USER, ROOT_PASSWORD);
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO evidence_artifact_version (case_id, artifact_key, version, source_system,"
                                 + " source_reference, content_sha256, availability, integrity_status,"
                                 + " captured_by, captured_at) VALUES (?,?,?,?,?,?,'RESOLVED','NOT_CHECKED',?,NOW())")) {
                ps.setLong(1, caseId);
                ps.setString(2, "core_banking:check-test");
                ps.setInt(3, 1);
                ps.setString(4, "CORE_BANKING");
                ps.setString(5, "CHECK-TEST");
                ps.setNull(6, java.sql.Types.CHAR);
                ps.setString(7, "analyst");
                ps.executeUpdate();
            }
        }).isInstanceOf(Exception.class)
                .hasMessageContaining("chk_artifact_resolved_has_digest");
    }

    // ==================== TP-27：真实库 epoch 推进后幂等重放返回原提交 ====================

    /** 此缺陷在单测中被"mock 不推进实体 epoch"掩盖；本用例用真实库验证。 */
    @Test
    void idempotentReplaySurvivesRealEpochAdvance() throws Exception {
        Long caseId = createV2CaseWithLinkedAlert("RC11");
        // 材料已抓取 + 已核验（RESOLVED + CONFIRMED 事件）
        evidenceSource.putFixture("CORE_BANKING", "RC11-DOC", "交易回单：合法货款结算（演示）");
        service.captureEvidence(caseId, "CORE_BANKING", "RC11-DOC", "analyst");
        Long artifactId = artifactRepository
                .findTopByCaseIdAndArtifactKeyOrderByVersionDesc(caseId, "core_banking:rc11-doc")
                .orElseThrow().getId();
        service.recordVerification(caseId, artifactId, "INDEPENDENT_SOURCE_CHECK",
                "来源内容与业务事实核对一致，观察与限制已记录", "覆盖冻结来源集合", "CONFIRMED", "verifier-x");

        ExplanationViews.UnitView unit = service.openWorkspace(caseId).units().get(0);
        resolveScopeIssue(caseId, unit.unitId());
        transactionTemplate.executeWithoutResult(status -> {
            try {
                service.saveDraft(caseId, unit.unitId(), unit.draftRevision(),
                        explainedSettlementDraft(objectMapperHolder(), artifactId), "analyst");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        ExplanationViews.UnitView fresh = service.openWorkspace(caseId).units().get(0);

        String token = service.reviewBasisToken(caseId);
        ExplanationViews.SubmissionResult first = service.submitUnit(caseId, fresh.unitId(),
                fresh.draftRevision(), token, "IDEMP-DB", "analyst");
        long epochAfterSubmit = caseRepository.findById(caseId).orElseThrow().getCaseFactsEpoch();
        assertThat(epochAfterSubmit).isGreaterThan(0); // 真实 epoch 已推进

        // 同 requestId 重放（payload 未变）→ 返回原提交，不误判 409
        ExplanationViews.SubmissionResult replay = service.submitUnit(caseId, fresh.unitId(),
                fresh.draftRevision() + 1, service.reviewBasisToken(caseId), "IDEMP-DB", "analyst");
        assertThat(replay.submissionId()).isEqualTo(first.submissionId());
        assertThat(replay.messages().get(0)).contains("幂等重放");
    }

    // ==================== RC-03（A6-01）：空证据 EXPLAINED 在真实事务中被拒 ====================

    @Test
    void emptyEvidenceExplainedIsRejectedInRealTransaction() throws Exception {
        Long caseId = createV2CaseWithLinkedAlert("RC03");
        // 不登记任何材料：六问题引用为空
        ExplanationViews.UnitView unit = service.openWorkspace(caseId).units().get(0);
        resolveScopeIssue(caseId, unit.unitId());
        String draftNoEvidence = explainedSettlementDraft(objectMapperHolder(), 1L)
                .replaceAll("\"artifactVersionIds\":\\[1\\]", "\"artifactVersionIds\":[]");
        transactionTemplate.executeWithoutResult(status -> {
            try {
                service.saveDraft(caseId, unit.unitId(), unit.draftRevision(), draftNoEvidence, "analyst");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        ExplanationViews.UnitView fresh = service.openWorkspace(caseId).units().get(0);
        assertThatThrownBy(() -> service.submitUnit(caseId, fresh.unitId(), fresh.draftRevision(),
                service.reviewBasisToken(caseId), "RC03-DB", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空引用不能形成可采用结论");
    }

    // ==================== 辅助 ====================

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapperHolder =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private com.fasterxml.jackson.databind.ObjectMapper objectMapperHolder() {
        return objectMapperHolder;
    }

    private String jdbcUrl() {
        return "jdbc:mysql://" + HOST + "/" + SCHEMA
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
    }

    /** 创建 v2 契约案件 + 1 条 LINKED 预警（触发单元创建）。 */
    private Long createV2CaseWithLinkedAlert(String tag) {
        return transactionTemplate.execute(status -> {
            // 使用演示客户 C001（快照含生成的 sourceRecordId=MOCK-C001-N 交易事实）
            CaseEntity c = new CaseEntity();
            c.setCustomerId("C001");
            c.setCustomerName(tag + "演示客户");
            c.setAlertRule("大额频繁跨国转账 / 夜间集中交易");
            c.setStatus(CaseStatus.HOLD);
            c.setInvestigationContractVersion(2);
            c.setCaseFactsEpoch(0);
            CaseEntity saved = caseRepository.save(c);

            AmlAlert alert = new AmlAlert();
            alert.setExternalAlertId(tag + "-ALERT-" + saved.getId());
            alert.setCustomerId(saved.getCustomerId());
            alert.setRuleCode("RAPID_MOVEMENT");
            alert.setScenarioCode("RAPID_MOVEMENT");
            alert.setHitReason("资金快进快出：入账后短期转出（演示合成命中）");
            alert.setOccurredAt(LocalDateTime.now());
            alert.setStatus(AlertStatus.LINKED);
            alert.setCaseId(saved.getId());
            alert.setRevision(0);
            alert.setCreatedBy("analyst");
            alertRepository.save(alert);

            InvestigationHypothesis hypothesis = new InvestigationHypothesis();
            hypothesis.setCaseId(saved.getId());
            hypothesis.setStatus(HypothesisStatus.OPEN);
            hypothesis.setRevision(0);
            hypothesis.setScenarioCode("RAPID_MOVEMENT");
            hypothesis.setHypothesisCode("HYP-" + tag + "-" + saved.getId());
            hypothesis.setTitle("资金快进快出假设（演示）");
            hypothesis.setInvestigationQuestion("入账资金是否在短期内以相近金额转出且缺少相符用途？");
            hypothesis.setRationale("初始判断依据");
            hypothesis.setRequiredEvidenceTypes("TRANSACTION");
            hypothesis.setCreatedBy("analyst");
            hypothesis.setUpdatedBy("analyst");
            hypothesisRepository.save(hypothesis);

            service.ensureUnitsForLinkedAlerts(saved.getId(),
                    alertRepository.findByCaseIdOrderByOccurredAtAsc(saved.getId()), "analyst");
            return saved.getId();
        });
    }

    /** 处置单元的范围枚举问题（业务前置：枚举方法已说明并冻结）。 */
    private void resolveScopeIssue(Long caseId, Long unitId) {
        service.openWorkspace(caseId).issues().stream()
                .filter(issue -> issue.issueKey().startsWith("ALERT_SCOPE_UNRESOLVED")
                        && unitId.equals(issue.unitId()) && "OPEN".equals(String.valueOf(issue.disposition())))
                .findFirst()
                .ifPresent(issue -> service.disposeIssue(caseId, issue.issueId(), issue.revision(),
                        "RESOLVED_WITH_EVIDENCE",
                        "范围以监测系统冻结命中清单为准：交易 T-1001 为该预警完整命中集合（演示合成数据）",
                        "MONITOR-HIT-LIST-" + caseId, "analyst"));
    }

    /** 从服务器冻结来源（DatabaseCustomerDataPort 快照）读取第一笔交易作为命中范围。 */
    private com.bank.aml.domain.TransactionRecord firstSourceTransaction() {
        List<com.bank.aml.domain.TransactionRecord> txns = customerDataPort.transactionsOf("C001");
        assertThat(txns).isNotEmpty();
        return txns.get(0);
    }

    private String explainedSettlementDraft(com.fasterxml.jackson.databind.ObjectMapper mapper,
                                            long artifactVersionId) {
        com.bank.aml.domain.TransactionRecord tx = firstSourceTransaction();
        var draft = mapper.createObjectNode();
        var policy = draft.putObject("policy");
        policy.put("businessRole", "境内贸易企业：自营商品采购与销售");
        policy.put("paymentStage", "DELIVERED_SETTLEMENT");
        policy.put("payerMatchesContractBuyer", true);
        policy.put("payeeMatchesContractSeller", true);
        var scope = draft.putObject("scope");
        scope.putArray("reviewedTransactionIds").add(tx.sourceRecordId());
        scope.put("scopeEnumerationNote", "以监测系统冻结命中清单为准，逐笔核对交易流水后枚举");
        scope.putObject("transactionAmounts").put(tx.sourceRecordId(), tx.amount().toPlainString());
        scope.putArray("allocations").addObject().put("transactionId", tx.sourceRecordId())
                .put("amount", tx.amount().toPlainString());
        var questions = draft.putObject("questions");
        for (String code : new String[]{"Q1", "Q2", "Q3", "Q4", "Q5", "Q6"}) {
            var question = questions.putObject(code);
            question.put("assessment", "SATISFIED");
            question.put("judgement", "事实已由来源核对支持，判断理由充分记录在案");
            question.put("factLocation", "CORE_BANKING 流水");
            question.put("factKind", "OBSERVED_FACT");
            question.put("verificationMethod", "INDEPENDENT_SOURCE_CHECK");
            question.put("limitations", "覆盖冻结来源集合");
            question.putArray("artifactVersionIds").add(artifactVersionId);
        }
        draft.put("outcome", "EXPLAINED");
        return draft.toString();
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
