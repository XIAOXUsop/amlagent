package com.bank.aml.explanation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.investigation.AlertInvestigationCoverageRepository;
import com.bank.aml.investigation.AmlAlertRepository;
import com.bank.aml.investigation.InvestigationHypothesisRepository;
import com.bank.aml.review.EnhancedDueDiligenceRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FR-01 防回归（v4 计划 §4.1 / RF-01 / RF-03）：
 * 每题独立核验链——Q1 的核验不能替 Q2 通过；同材料不同事实互不错误覆盖；
 * CONFIRMED→UNRESOLVED 后旧提交失效，有效补核验后可重提（恢复路径不阻断）。
 */
class EvidenceAdmissibilityTest {

    private static final Long CASE_ID = 7L;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"),
            ZoneId.of("Asia/Shanghai"));

    private final EvidenceArtifactVersionRepository artifacts =
            mock(EvidenceArtifactVersionRepository.class);
    private final EvidenceVerificationEventRepository verifications =
            mock(EvidenceVerificationEventRepository.class);

    private final EvidenceAdmissibilityService service =
            new EvidenceAdmissibilityService(artifacts, verifications);

    private Long nextId = 1L;
    /** 事件存储：artifactVersionId → factKey(null=材料级) → 事件链（append 后生效）。 */
    private final java.util.Map<Long, java.util.Map<String, java.util.List<EvidenceVerificationEvent>>>
            eventChains = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        when(artifacts.findByIdAndCaseId(any(), ArgumentMatchers.eq(CASE_ID))).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            if (!Long.valueOf(1L).equals(id)) {
                return Optional.empty();
            }
            EvidenceArtifactVersion artifact = new EvidenceArtifactVersion();
            setId(artifact, 1L);
            artifact.setCaseId(CASE_ID);
            artifact.setArtifactKey("core_banking:doc-1");
            artifact.setVersion(1);
            artifact.setSourceSystem("CORE_BANKING");
            artifact.setContentSha256("a".repeat(64));
            artifact.setAvailability("RESOLVED");
            artifact.setIntegrityStatus("NOT_CHECKED");
            artifact.setCapturedBy("analyst");
            return Optional.of(artifact);
        });
        when(verifications.findByArtifactVersionIdAndSubjectFactKeyOrderByEventTimeAscIdAsc(any(), any()))
                .thenAnswer(inv -> chain(inv.getArgument(0, Long.class), inv.getArgument(1, String.class)));
        when(verifications.findByArtifactVersionIdAndSubjectFactKeyIsNullOrderByEventTimeAscIdAsc(any()))
                .thenAnswer(inv -> chain(inv.getArgument(0, Long.class), null));
    }

    private java.util.List<EvidenceVerificationEvent> chain(Long artifactVersionId, String factKey) {
        return eventChains
                .getOrDefault(artifactVersionId, java.util.Map.of())
                .getOrDefault(factKey, List.of());
    }

    private void appendEvent(Long artifactVersionId, String factKey, String result) {
        EvidenceVerificationEvent event = new EvidenceVerificationEvent();
        setId(event, nextId++);
        event.setCaseId(CASE_ID);
        event.setArtifactVersionId(artifactVersionId);
        event.setMethod("INDEPENDENT_SOURCE_CHECK");
        event.setObservedFacts("来源内容与该事实核对，观察与限制已记录");
        event.setResult(result);
        event.setActor("verifier-x");
        event.setSubjectFactKey(factKey);
        eventChains.computeIfAbsent(artifactVersionId, k -> new java.util.HashMap<>())
                .computeIfAbsent(factKey, k -> new java.util.ArrayList<>())
                .add(event);
    }

    // ---- RF-03：Q2 只用未核验材料，Q1 有核验 → Q2 单独阻断，不能借用 Q1 ----

    @Test
    void q2CannotBorrowQ1Verification() {
        // Q1 已有材料级 CONFIRMED；Q2 引用同一材料但该材料在 Q2 事实上无核验
        appendEvent(1L, "Q1", "CONFIRMED");

        assertThat(service.assessQuestion(CASE_ID,
                new EvidenceAdmissibilityService.SubjectEvidence("Q1", List.of(1L))).admissible())
                .isTrue();
        EvidenceAdmissibilityService.AdmissibilityResult q2 = service.assessQuestion(CASE_ID,
                new EvidenceAdmissibilityService.SubjectEvidence("Q2", List.of(1L)));
        assertThat(q2.admissible()).isFalse();
        assertThat(q2.blockerCode()).isEqualTo("VERIFICATION_MISSING");
        assertThat(q2.remediation()).contains("其它问题的核验不能替代本题");
    }

    // ---- 同一材料不同事实的核验互不错误覆盖 ----

    @Test
    void differentFactKeysDoNotOverrideEachOther() {
        appendEvent(1L, "Q2", "UNRESOLVED"); // Q2 事实失去支持
        appendEvent(1L, "Q3", "CONFIRMED");  // Q3 事实有效

        assertThat(service.assessQuestion(CASE_ID,
                new EvidenceAdmissibilityService.SubjectEvidence("Q3", List.of(1L))).admissible())
                .isTrue();
        EvidenceAdmissibilityService.AdmissibilityResult q2 = service.assessQuestion(CASE_ID,
                new EvidenceAdmissibilityService.SubjectEvidence("Q2", List.of(1L)));
        assertThat(q2.blockerCode()).isEqualTo("VERIFICATION_LOST");
    }

    // ---- RF-01：最新核验从确认改为无法确认 → 不再提供肯定支持；补核验后恢复 ----

    @Test
    void unresolvedSupersedesConfirmedThenRestoredByNewConfirmation() {
        appendEvent(1L, "Q4", "CONFIRMED");
        assertThat(service.effectiveSupport(1L, "Q4"))
                .isEqualTo(EvidenceAdmissibilityService.SupportStatus.CONFIRMED);

        // 追加 UNRESOLVED（更正/失去支持）→ 链内最新状态生效
        appendEvent(1L, "Q4", "UNRESOLVED");
        assertThat(service.effectiveSupport(1L, "Q4"))
                .isEqualTo(EvidenceAdmissibilityService.SupportStatus.LOST);
        assertThat(service.assessQuestion(CASE_ID,
                new EvidenceAdmissibilityService.SubjectEvidence("Q4", List.of(1L))).blockerCode())
                .isEqualTo("VERIFICATION_LOST");

        // 有效补核验 → 恢复支持（调查可继续，生成新提交；旧失效版本保留）
        appendEvent(1L, "Q4", "CONFIRMED");
        assertThat(service.assessQuestion(CASE_ID,
                new EvidenceAdmissibilityService.SubjectEvidence("Q4", List.of(1L))).admissible())
                .isTrue();
    }

    // ---- 结构化 blocker 完整性 ----

    @Test
    void structuredBlockerCarriesQuestionAndRemediation() {
        EvidenceAdmissibilityService.AdmissibilityResult empty =
                service.assessQuestion(CASE_ID, new EvidenceAdmissibilityService.SubjectEvidence("Q5", List.of()));
        assertThat(empty.blockerCode()).isEqualTo("EVIDENCE_EMPTY");
        assertThat(empty.questionCode()).isEqualTo("Q5");
        assertThat(empty.remediation()).isNotBlank();
    }

    // ---- 端到端：经 ExplanationWorkspaceService 提交路径的每题隔离（成功样例防"一律拒绝"） ----

    @Test
    void fullSubmissionWithPerQuestionVerificationPasses() throws Exception {
        // 使用真实 service 组合验证：所有题引用材料 1，全部题在该题事实上有核验 → 可提交
        java.util.Map<Long, java.util.Map<String, java.util.List<EvidenceVerificationEvent>>> saved = eventChains;
        for (String code : List.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6")) {
            appendEvent(1L, code, "CONFIRMED");
        }
        assertThatCode(() -> {
            for (String code : List.of("Q1", "Q2", "Q3", "Q4", "Q5", "Q6")) {
                var verdict = service.assessQuestion(CASE_ID,
                        new EvidenceAdmissibilityService.SubjectEvidence(code, List.of(1L)));
                if (!verdict.admissible()) {
                    throw new IllegalStateException(verdict.remediation());
                }
            }
        }).doesNotThrowAnyException();
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
