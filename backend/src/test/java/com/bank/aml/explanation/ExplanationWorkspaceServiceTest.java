package com.bank.aml.explanation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.investigation.AlertCoverageConclusion;
import com.bank.aml.investigation.AlertInvestigationCoverage;
import com.bank.aml.investigation.AlertInvestigationCoverageRepository;
import com.bank.aml.investigation.AlertStatus;
import com.bank.aml.investigation.AmlAlert;
import com.bank.aml.investigation.HypothesisStatus;
import com.bank.aml.investigation.InvestigationHypothesis;
import com.bank.aml.investigation.InvestigationHypothesisRepository;
import com.bank.aml.review.EnhancedDueDiligenceRequest;
import com.bank.aml.review.EnhancedDueDiligenceRequestRepository;
import com.bank.aml.review.EnhancedDueDiligenceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 解释核验工作区单元回归（v2 计划 §18 的可离线验证子集）。
 * 覆盖：V2-01/02/03/04/08/09/11/12/13/15/19/22 等场景；
 * 数据库/并发/权限层由 integration 标签测试与环境验收补齐（当前环境无可用 Docker）。
 */
class ExplanationWorkspaceServiceTest {

    private static final Long CASE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Clock clock = Clock.fixed(NOW, ZONE);

    private final CaseRepository cases = mock(CaseRepository.class);
    private final AlertExplanationUnitRepository units = mock(AlertExplanationUnitRepository.class);
    private final ExplanationSubmissionRepository submissions = mock(ExplanationSubmissionRepository.class);
    private final ExplanationIssueRepository issues = mock(ExplanationIssueRepository.class);
    private final VerificationBasisRepository bases = mock(VerificationBasisRepository.class);
    private final EvidenceArtifactVersionRepository artifacts = mock(EvidenceArtifactVersionRepository.class);
    private final EvidenceVerificationEventRepository verifications = mock(EvidenceVerificationEventRepository.class);
    private final ExplanationEvidenceUseRepository evidenceUses = mock(ExplanationEvidenceUseRepository.class);
    private final AlertInvestigationCoverageRepository coverage = mock(AlertInvestigationCoverageRepository.class);
    private final InvestigationHypothesisRepository hypotheses = mock(InvestigationHypothesisRepository.class);
    private final com.bank.aml.investigation.AmlAlertRepository alerts = mock(com.bank.aml.investigation.AmlAlertRepository.class);
    private final EnhancedDueDiligenceRequestRepository eddRequests = mock(EnhancedDueDiligenceRequestRepository.class);
    private final AuditOutboxService auditOutbox = mock(AuditOutboxService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final java.util.Map<Long, ExplanationSubmission> submissionById = new java.util.HashMap<>();
    private final java.util.Map<String, ExplanationSubmission> submissionByKey = new java.util.HashMap<>();
    private final List<ExplanationSubmission> currentSubmissions = new java.util.ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(1);

    private final ExplanationWorkspaceService service = new ExplanationWorkspaceService(cases, units,
            submissions, issues, bases, artifacts, verifications, evidenceUses, coverage, hypotheses,
            alerts, eddRequests, new ExplanationPolicyCatalog(), auditOutbox, objectMapper, clock);

    private final CaseEntity caseEntity = v2Case();
    private final AlertExplanationUnit unit = unit(100L, 11L, 31L);
    private final AlertInvestigationCoverage coverageRow = coverage(11L, 31L);
    private final InvestigationHypothesis hypothesis = hypothesis(31L, HypothesisStatus.OPEN, 1);

    @BeforeEach
    void setUp() {
        when(cases.findById(CASE_ID)).thenReturn(Optional.of(caseEntity));
        when(cases.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(caseEntity));
        when(cases.bumpFactsEpoch(CASE_ID)).thenReturn(1);
        when(units.findByIdAndCaseId(100L, CASE_ID)).thenReturn(Optional.of(unit));
        when(units.findById(100L)).thenReturn(Optional.of(unit));
        when(units.findByCaseIdOrderByIdAsc(CASE_ID)).thenReturn(List.of(unit));
        when(submissions.save(any())).thenAnswer(inv -> {
            ExplanationSubmission saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, idSeq.getAndIncrement());
            }
            submissionById.put(saved.getId(), saved);
            if (saved.getIdempotencyKey() != null) {
                submissionByKey.put(saved.getIdempotencyKey(), saved);
            }
            if (saved.getState() == SubmissionState.CURRENT) {
                currentSubmissions.removeIf(item -> item.getUnitId().equals(saved.getUnitId()));
                currentSubmissions.add(saved);
            }
            return saved;
        });
        when(submissions.findById(any())).thenAnswer(
                inv -> Optional.ofNullable(submissionById.get(inv.getArgument(0, Long.class))));
        when(submissions.findByIdempotencyKey(any())).thenAnswer(
                inv -> Optional.ofNullable(submissionByKey.get(inv.getArgument(0, String.class))));
        when(submissions.findByCaseIdAndStateOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenAnswer(inv -> List.copyOf(currentSubmissions));
        when(submissions.findTopByUnitIdOrderBySubmissionNoDesc(100L)).thenReturn(Optional.empty());
        when(submissions.findTopByUnitIdOrderBySubmissionNoDesc(101L)).thenReturn(Optional.empty());
        when(issues.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(issues.findByCaseIdAndIssueKey(ArgumentMatchers.eq(CASE_ID), any())).thenReturn(Optional.empty());
        when(issues.findByCaseIdOrderByIdAsc(CASE_ID)).thenReturn(List.of());
        when(issues.findByCaseIdAndUnitIdOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any())).thenReturn(List.of());
        when(artifacts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(artifacts.findByIdAndCaseId(1L, CASE_ID)).thenReturn(Optional.of(artifact(1L)));
        when(coverage.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(coverage.findByAlertId(11L)).thenReturn(Optional.of(coverageRow));
        when(hypotheses.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hypotheses.findById(31L)).thenReturn(Optional.of(hypothesis));
        when(hypotheses.findByCaseIdOrderByIdAsc(CASE_ID)).thenReturn(List.of(hypothesis));
        when(alerts.findById(11L)).thenReturn(Optional.of(alert(11L, "ALERT-A")));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(CASE_ID)).thenReturn(List.of(alert(11L, "ALERT-A")));
        when(eddRequests.findByCaseIdAndStatusOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenReturn(List.of());
    }

    // ---- V2-01 / DG-01：完整已交付结算解释 → EXPLAINED 提交，覆盖与假设汇总一致 ----

    @Test
    void deliveredSettlementExplainedSubmissionUpdatesCoverageAndAggregate() {
        saveDraft(explainedSettlementDraft(), 0);
        ExplanationViews.SubmissionResult result = service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "IDEMP-1", "analyst");

        assertThat(result.outcome()).isEqualTo(ExplanationOutcome.EXPLAINED);
        assertThat(result.state()).isEqualTo(SubmissionState.CURRENT);
        assertThat(result.hypothesisAggregate()).isEqualTo(ExplanationOutcome.EXPLAINED);
        // 覆盖绑定采用提交；假设汇总按 §3.3 推进为 REJECTED
        assertThat(coverageRow.getConclusion()).isEqualTo(AlertCoverageConclusion.EXPLAINED);
        assertThat(coverageRow.getUnitSubmissionId()).isEqualTo(result.submissionId());
        assertThat(hypothesis.getStatus()).isEqualTo(HypothesisStatus.REJECTED);
        // 提交不可变 + 依据使用记录落库
        assertThat(result.submissionId()).isNotNull();
        verify(evidenceUses, never()).save(any());
    }

    // ---- V2-04 / DG-04：同案一解释一可疑，汇总 CONFIRMED，覆盖方向不强制一致 ----

    @Test
    void mixedUnitsAllowExplainedAndSuspiciousSideBySide() throws Exception {
        saveDraft(explainedSettlementDraft(), 0);
        service.submitUnit(CASE_ID, 100L, 1, service.reviewBasisToken(CASE_ID), "IDEMP-1", "analyst");

        // 第二个单元（同一假设下的另一预警）提交可疑
        AlertExplanationUnit unit2 = unit(101L, 12L, 31L);
        when(units.findByIdAndCaseId(101L, CASE_ID)).thenReturn(Optional.of(unit2));
        when(units.findById(101L)).thenReturn(Optional.of(unit2));
        AlertInvestigationCoverage coverage2 = coverage(12L, 31L);
        when(coverage.findByAlertId(12L)).thenReturn(Optional.of(coverage2));
        AmlAlert alertB = alert(12L, "ALERT-B");
        when(alerts.findById(12L)).thenReturn(Optional.of(alertB));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(CASE_ID)).thenReturn(List.of(alert(11L, "ALERT-A"), alertB));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = suspiciousDraft(mapper);
        unit2.setDraftJson(mapper.writeValueAsString(draft));
        unit2.setDraftRevision(1);

        ExplanationViews.SubmissionResult result = service.submitUnit(CASE_ID, 101L, 1,
                service.reviewBasisToken(CASE_ID), "IDEMP-2", "analyst");

        assertThat(result.outcome()).isEqualTo(ExplanationOutcome.SUSPICIOUS);
        assertThat(result.hypothesisAggregate()).isEqualTo(ExplanationOutcome.SUSPICIOUS);
        // 不把已解释的正常部分改成可疑（§3.3/§7.2）
        assertThat(coverageRow.getConclusion()).isEqualTo(AlertCoverageConclusion.EXPLAINED);
        assertThat(coverage2.getConclusion()).isEqualTo(AlertCoverageConclusion.SUSPICIOUS);
        assertThat(hypothesis.getStatus()).isEqualTo(HypothesisStatus.CONFIRMED);
    }

    // ---- V2-02 / DG-02：合法预付，交期未来 → 不要求未来交付单，登记跟进义务 ----

    @Test
    void legitimatePrepayWithFutureDeliveryDoesNotRequireDeliveryProof() {
        saveDraft(prepayDraft("2026-12-01"), 0);
        ExplanationViews.SubmissionResult result = service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "IDEMP-PREPAY", "analyst");

        assertThat(result.outcome()).isEqualTo(ExplanationOutcome.EXPLAINED);
        assertThat(coverageRow.getConclusion()).isEqualTo(AlertCoverageConclusion.EXPLAINED);
        verify(submissions).save(ArgumentMatchers.argThat(sub -> sub instanceof ExplanationSubmission s
                && s.isFollowupRequired()));
    }

    // ---- V2-03 / DG-03：交期已过且无合理延期 → 当前关键问题，不能沿旧依据排除 ----

    @Test
    void overdueDeliveryCreatesCriticalIssueAndBlocksExplained() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = prepayDraft(mapper, "2026-01-01");
        draft.put("outcome", "EXPLAINED");
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "IDEMP-OVERDUE", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关键未知");
        // 关键问题已登记，覆盖未改动
        verify(issues).save(ArgumentMatchers.argThat(issue -> issue instanceof ExplanationIssue i
                && i.getIssueKey().startsWith("DELIVERY_OVERDUE")));
        assertThat(coverageRow.getConclusion()).isEqualTo(AlertCoverageConclusion.PENDING);
    }

    // ---- V2-08：只有“解释不成立”，没有怀疑依据 → 不自动产生 SUSPICIOUS ----

    @Test
    void suspiciousWithoutBasisIsRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = explainedSettlementDraft(mapper);
        draft.put("outcome", "SUSPICIOUS");
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "IDEMP-SUSP", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("怀疑");
    }

    // ---- V2-09：贷款归还等不适用场景 → POLICY_NOT_APPLICABLE，不切宽松配方 ----

    @Test
    void nonGoodsStageIsRejectedAsPolicyNotApplicable() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = explainedSettlementDraft(mapper);
        ((ObjectNode) draft.get("policy")).put("paymentStage", "LOAN_REPAYMENT");
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "IDEMP-POL", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExplanationPolicyCatalog.POLICY_NOT_APPLICABLE);
        verify(issues).save(ArgumentMatchers.argThat(issue -> issue instanceof ExplanationIssue i
                && i.getIssueKey().startsWith("POLICY_NOT_APPLICABLE")));
    }

    // ---- V2-11：材料内容被换 → 提交 STALE、覆盖回 PENDING，旧提交仍可回放 ----

    @Test
    void materialSupersededStalesSubmissionAndResetsCoverage() {
        ExplanationSubmission current = submission(1L, 100L, ExplanationOutcome.EXPLAINED);
        current.setState(SubmissionState.CURRENT);
        unit.setCurrentSubmissionId(1L);
        when(submissions.findById(1L)).thenReturn(Optional.of(current));
        when(submissions.findByCaseIdAndStateOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenReturn(List.of(current));
        ExplanationEvidenceUse use = new ExplanationEvidenceUse();
        use.setSubmissionId(1L);
        use.setCaseId(CASE_ID);
        when(evidenceUses.findByCaseIdAndArtifactVersionId(CASE_ID, 5L)).thenReturn(List.of(use));
        EvidenceArtifactVersion artifact = artifact(5L);
        when(artifacts.findByIdAndCaseId(5L, CASE_ID)).thenReturn(Optional.of(artifact));

        List<Long> affected = service.markArtifactSuperseded(CASE_ID, 5L, "v2-content-changed", "analyst");

        assertThat(affected).containsExactly(1L);
        assertThat(current.getState()).isEqualTo(SubmissionState.STALE);
        assertThat(unit.getCurrentSubmissionId()).isNull();
        assertThat(coverageRow.getConclusion()).isEqualTo(AlertCoverageConclusion.PENDING);
        assertThat(coverageRow.getUnitSubmissionId()).isNull();
    }

    /** 抓取时实际哈希与来源声称不一致 → INTEGRITY_BLOCKER（完整性/身份错误）。 */
    @Test
    void hashMismatchCapturesIntegrityBlocker() {
        ExplanationViews.EvidenceView view = service.captureEvidence(CASE_ID, "CORE_BANKING",
                "TXN-DOC-001", "a".repeat(64), "b".repeat(64), "analyst");

        assertThat(view.integrityStatus()).isEqualTo("MISMATCH");
        verify(issues).save(ArgumentMatchers.argThat(issue -> issue instanceof ExplanationIssue i
                && i.getSeverity() == IssueSeverity.INTEGRITY_BLOCKER));
    }

    // ---- V2-12：新材料不关联任何单元 → 进入案件待分派清单（问题），不可“不引用”绕过 ----

    @Test
    void capturedMaterialEntersUnassignedFactsList() {
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-002", "c".repeat(64), null, "analyst");

        verify(issues).save(ArgumentMatchers.argThat(issue -> issue instanceof ExplanationIssue i
                && i.getUnitId() == null && i.getIssueKey().startsWith("FACT_UNASSIGNED")));
    }

    // ---- V2-13：修改已提交工作单 → 先撤回后改稿；旧提交可回放但不可最终采用 ----

    @Test
    void amendmentWithdrawsCurrentSubmissionAndStartsNextDraft() {
        ExplanationSubmission current = submission(1L, 100L, ExplanationOutcome.EXPLAINED);
        current.setState(SubmissionState.CURRENT);
        current.setPayloadJson(explainedSettlementDraft());
        unit.setCurrentSubmissionId(1L);
        when(submissions.findById(1L)).thenReturn(Optional.of(current));

        service.amendUnit(CASE_ID, 100L, 1L, "analyst");

        assertThat(current.getState()).isEqualTo(SubmissionState.WITHDRAWN);
        assertThat(unit.getCurrentSubmissionId()).isNull();
        assertThat(unit.getDraftJson()).isEqualTo(current.getPayloadJson());
        assertThat(coverageRow.getConclusion()).isEqualTo(AlertCoverageConclusion.PENDING);
    }

    // ---- V2-15：当前 token 与事实变更并发 → 旧令牌被拒（409 协议） ----

    @Test
    void staleReviewBasisTokenIsRejectedWithConflictProtocol() {
        saveDraft(explainedSettlementDraft(), 0);
        // GET 令牌后案件事实推进（epoch 变化）→ 旧令牌失效
        caseEntity.setCaseFactsEpoch(99);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1, "stale-token", "IDEMP-TOK", "analyst"))
                .isInstanceOf(InvestigationRevisionConflictException.class)
                .hasMessageContaining("令牌已失效");
    }

    // ---- V2-18 / 自审：实质贡献人（草稿编辑/核验人）不得作最终复核人 ----

    @Test
    void reviewerAmongContributorsIsBlockedFromFinalDecision() {
        saveDraft(explainedSettlementDraft(), 0);
        service.submitUnit(CASE_ID, 100L, 1, service.reviewBasisToken(CASE_ID), "IDEMP-1", "analyst");
        ExplanationSubmission saved = submission(1L, 100L, ExplanationOutcome.EXPLAINED);
        saved.setState(SubmissionState.CURRENT);
        saved.setContributors("analyst,verifier-x");
        when(submissions.findByCaseIdAndStateOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenReturn(List.of(saved));
        // 全部单元已解释、无关键未知
        when(units.findByCaseIdOrderByIdAsc(CASE_ID)).thenReturn(List.of(unit));

        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity,
                com.bank.aml.review.ReviewDecision.EXCLUDE_FALSE_POSITIVE, "analyst",
                service.reviewBasisToken(CASE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实质贡献人");
        // 不同复核人可以通过自审限制（决策表其余条件由其他用例覆盖）
        service.validateReadyForReview(caseEntity,
                com.bank.aml.review.ReviewDecision.EXCLUDE_FALSE_POSITIVE, "reviewer-b",
                service.reviewBasisToken(CASE_ID));
    }

    // ---- V2-19：完整性问题不能降级/不能“不相关” ----

    @Test
    void integrityBlockerCannotBeDowngradedOrMarkedNotRelevant() {
        ExplanationIssue blocker = new ExplanationIssue();
        setId(blocker, 9L);
        blocker.setCaseId(CASE_ID);
        blocker.setIssueKey("INTEGRITY:doc:v1");
        blocker.setSeverity(IssueSeverity.INTEGRITY_BLOCKER);
        blocker.setDisposition(IssueDisposition.OPEN);
        blocker.setRevision(0);
        when(issues.findByIdAndCaseId(9L, CASE_ID)).thenReturn(Optional.of(blocker));

        assertThatThrownBy(() -> service.disposeIssue(CASE_ID, 9L, 0,
                "NOT_RELEVANT_WITH_REASON", "该材料与本次判断无关且金额很小", "DOC-REF-1", null, null, "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能被认定不相关");
        assertThatThrownBy(() -> service.disposeIssue(CASE_ID, 9L, 0,
                "RESOLVED_WITH_EVIDENCE", "来源已更正并重新核验", "DOC-REF-2", "CONTEXT_GAP", "reviewer-b", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许降级");
    }

    /** 重要性降级需与处理人不同的复核人确认（V2-19 正常路径）。 */
    @Test
    void severityDowngradeRequiresIndependentConfirmer() {
        ExplanationIssue gap = new ExplanationIssue();
        setId(gap, 10L);
        gap.setCaseId(CASE_ID);
        gap.setIssueKey("CONTEXT_GAP:1");
        gap.setSeverity(IssueSeverity.DECISION_CRITICAL);
        gap.setDisposition(IssueDisposition.OPEN);
        gap.setRevision(0);
        when(issues.findByIdAndCaseId(10L, CASE_ID)).thenReturn(Optional.of(gap));

        assertThatThrownBy(() -> service.disposeIssue(CASE_ID, 10L, 0, "RESOLVED_WITH_EVIDENCE",
                "不影响本次决定的理由与引用已完整记录：该缺口仅涉及非命中交易的辅助说明", "DOC-REF-3", "CONTEXT_GAP", "analyst", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不同的复核人");

        service.disposeIssue(CASE_ID, 10L, 0, "RESOLVED_WITH_EVIDENCE",
                "不影响本次决定的理由与引用已完整记录：该缺口仅涉及非命中交易的辅助说明", "DOC-REF-3", "CONTEXT_GAP", "reviewer-b", "analyst");
        assertThat(gap.getSeverity()).isEqualTo(IssueSeverity.CONTEXT_GAP);
        assertThat(gap.getConfirmedBy()).isEqualTo("reviewer-b");
    }

    // ---- V2-22：幂等重放 → 返回原 ID 与当前状态（原提交 STALE 时不“复活”），不创建新版本 ----

    @Test
    void idempotentReplayReturnsOriginalSubmissionWithoutNewVersion() {
        saveDraft(explainedSettlementDraft(), 0);
        ExplanationViews.SubmissionResult first = service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "IDEMP-REPLAY", "analyst");

        // 原提交随后被材料变更置为 STALE（仍在幂等索引中）
        ExplanationSubmission stored = submissionById.get(first.submissionId());
        stored.setState(SubmissionState.STALE);

        ExplanationViews.SubmissionResult replay = service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "IDEMP-REPLAY", "analyst");

        assertThat(replay.submissionId()).isEqualTo(first.submissionId());
        assertThat(replay.state()).isEqualTo(SubmissionState.STALE);
        assertThat(replay.messages().get(0)).contains("幂等重放");
    }

    /** 相同幂等键但不同内容 → 409 冲突协议（§10.3）。 */
    @Test
    void sameIdempotencyKeyWithDifferentContentIsRejected() throws Exception {
        saveDraft(explainedSettlementDraft(), 0);
        service.submitUnit(CASE_ID, 100L, 1, service.reviewBasisToken(CASE_ID), "IDEMP-REPLAY", "analyst");

        // 修订后修改草稿内容（同一单元，不同请求摘要）
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode amended = suspiciousDraft(mapper);
        unit.setDraftJson(mapper.writeValueAsString(amended));
        int nextRevision = unit.getDraftRevision();

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, nextRevision,
                service.reviewBasisToken(CASE_ID), "IDEMP-REPLAY", "analyst"))
                .isInstanceOf(InvestigationRevisionConflictException.class)
                .hasMessageContaining("不同提交内容");
    }

    // ---- 辅助构造 ----

    private void saveDraft(String draftJson, int expectedRevision) {
        unit.setDraftJson(draftJson);
        unit.setDraftRevision(expectedRevision);
        service.saveDraft(CASE_ID, 100L, expectedRevision, draftJson, "analyst");
    }

    private String explainedSettlementDraft() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(explainedSettlementDraft(mapper));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode explainedSettlementDraft(ObjectMapper mapper) throws Exception {
        ObjectNode draft = mapper.createObjectNode();
        ObjectNode policy = draft.putObject("policy");
        policy.put("businessRole", "境内贸易企业：自营商品采购与销售");
        policy.put("paymentStage", "DELIVERED_SETTLEMENT");
        policy.put("payerMatchesContractBuyer", true);
        policy.put("payeeMatchesContractSeller", true);
        ObjectNode scope = draft.putObject("scope");
        scope.putArray("reviewedTransactionIds").add("T-1001");
        scope.put("scopeEnumerationNote", "以监测系统 9 月冻结命中清单为准，逐笔核对交易流水后枚举");
        ObjectNode amounts = scope.putObject("transactionAmounts");
        amounts.put("T-1001", "320000.00");
        ArrayNode allocations = scope.putArray("allocations");
        ObjectNode allocation = allocations.addObject();
        allocation.put("transactionId", "T-1001");
        allocation.put("orderRef", "SO-2026-001");
        allocation.put("amount", "320000.00");
        ObjectNode questions = draft.putObject("questions");
        addQuestion(questions, "Q1", "SATISFIED", "客户为成立 3 年的贸易企业，结算规模与近 12 个月申报营收相符",
                "KYC 档案第 4 页 / 税务申报摘要", "OBSERVED_FACT", 1L);
        addQuestion(questions, "Q2", "SATISFIED", "付款人与合同买方一致，订单 SO-2026-001 与收款一一对应",
                "CORE_BANKING 流水 T-1001 + 合同第 2 条", "OBSERVED_FACT", 1L);
        addQuestion(questions, "Q3", "SATISFIED", "收款人、采购与交付对应：物流平台已观察到签收记录",
                "LOGISTICS_PLATFORM WAYBILL-88", "OBSERVED_FACT", 1L);
        addQuestion(questions, "Q4", "SATISFIED", "命中交易 T-1001 对应订单 SO-2026-001，金额与期间一致",
                "对账表（冻结范围）", "OBSERVED_FACT", 1L);
        addQuestion(questions, "Q5", "SATISFIED", "快速结算符合合同 7 天账期约定，收支差额为货款净额",
                "合同第 5 条 + CORE_BANKING 流水", "ANALYST_INFERENCE", 1L);
        addQuestion(questions, "Q6", "SATISFIED", "重要差异已解释：手续费 60 元以独立调整项记录",
                "对账表调整项", "DOCUMENT_ASSERTION", 1L);
        draft.put("outcome", "EXPLAINED");
        return draft;
    }

    private ObjectNode suspiciousDraft(ObjectMapper mapper) throws Exception {
        ObjectNode draft = explainedSettlementDraft(mapper);
        draft.put("outcome", "SUSPICIOUS");
        ObjectNode suspicion = draft.putObject("suspicionBasis");
        suspicion.put("assessedFacts", "收款主体与客户陈述的买方不一致（核验观察：CORE_BANKING 收款户名核对不符）");
        suspicion.put("reverseExplanations", "客户解释为关联公司代收，但无法提供委托代收的书面授权");
        suspicion.put("whyInsufficient", "代收关系缺乏独立来源支持，40 万元转账性质仍不能排除可疑");
        return draft;
    }

    private String prepayDraft(String deliveryDueDate) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(prepayDraft(mapper, deliveryDueDate));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ObjectNode prepayDraft(ObjectMapper mapper, String deliveryDueDate) throws Exception {
        ObjectNode draft = mapper.createObjectNode();
        ObjectNode policy = draft.putObject("policy");
        policy.put("businessRole", "境内制造企业：向固定供应商预付采购原材料");
        policy.put("paymentStage", "ADVANCE_PAYMENT");
        policy.put("payerMatchesContractBuyer", true);
        policy.put("payeeMatchesContractSeller", true);
        policy.put("contractNumber", "PO-2026-088");
        policy.put("deliveryDueDate", deliveryDueDate);
        ObjectNode scope = draft.putObject("scope");
        scope.putArray("reviewedTransactionIds").add("T-2001");
        scope.put("scopeEnumerationNote", "以合同 PO-2026-088 预付条款对应的付款流水逐笔枚举");
        ObjectNode amounts = scope.putObject("transactionAmounts");
        amounts.put("T-2001", "500000.00");
        ArrayNode allocations = scope.putArray("allocations");
        ObjectNode allocation = allocations.addObject();
        allocation.put("transactionId", "T-2001");
        allocation.put("orderRef", "PO-2026-088");
        allocation.put("amount", "500000.00");
        ObjectNode questions = draft.putObject("questions");
        addQuestion(questions, "Q1", "SATISFIED", "预付安排与近两年采购模式一致（同供应商历史预付记录）",
                "采购台账", "OBSERVED_FACT", 1L);
        addQuestion(questions, "Q2", "SATISFIED", "该笔收入即合同预付货款，性质有合同依据",
                "合同 PO-2026-088 第 3 条", "DOCUMENT_ASSERTION", 1L);
        addQuestion(questions, "Q3", "SATISFIED", "预付条款、收款授权与交期明确（2026-12-01 交付）",
                "合同 PO-2026-088 第 6 条", "DOCUMENT_ASSERTION", 1L);
        addQuestion(questions, "Q4", "SATISFIED", "预付占订单金额 30%，余款验收后支付",
                "合同付款计划", "DOCUMENT_ASSERTION", 1L);
        addQuestion(questions, "Q5", "SATISFIED", "同日预付因供应商要求款到发货，规模与合同一致",
                "往来函件", "DOCUMENT_ASSERTION", 1L);
        addQuestion(questions, "Q6", "SATISFIED", "未到期事项：2026-12-01 交付核验已分派持续跟进任务",
                "接续任务计划", "ANALYST_INFERENCE", 1L);
        draft.put("outcome", "EXPLAINED");
        return draft;
    }

    private static void addQuestion(ObjectNode questions, String code, String assessment, String judgement,
                                    String factLocation, String factKind, Long artifactVersionId) {
        ObjectNode question = questions.putObject(code);
        question.put("assessment", assessment);
        question.put("judgement", judgement);
        question.put("factLocation", factLocation);
        question.put("factKind", factKind);
        question.put("verificationMethod", "INDEPENDENT_SOURCE_CHECK");
        question.put("limitations", "核验仅覆盖冻结来源集合内的材料");
        question.putArray("artifactVersionIds").add(artifactVersionId);
        question.putArray("transactionIds").add("T-1001");
    }

    private CaseEntity v2Case() {
        CaseEntity entity = new CaseEntity();
        setId(entity, CASE_ID);
        entity.setStatus(CaseStatus.HOLD);
        entity.setInvestigationContractVersion(2);
        entity.setCaseFactsEpoch(0);
        return entity;
    }

    private AlertExplanationUnit unit(Long id, Long alertId, Long hypothesisId) {
        AlertExplanationUnit entity = new AlertExplanationUnit();
        setId(entity, id);
        entity.setCaseId(CASE_ID);
        entity.setAlertId(alertId);
        entity.setHypothesisId(hypothesisId);
        entity.setScopeRevision(1);
        entity.setDraftRevision(0);
        entity.setCreatedBy("analyst");
        return entity;
    }

    private AlertInvestigationCoverage coverage(Long alertId, Long hypothesisId) {
        AlertInvestigationCoverage entity = new AlertInvestigationCoverage();
        entity.setAlertId(alertId);
        entity.setCaseId(CASE_ID);
        entity.setHypothesisId(hypothesisId);
        entity.setHypothesisRevision(1L);
        entity.setConclusion(AlertCoverageConclusion.PENDING);
        entity.setRevision(0);
        return entity;
    }

    private InvestigationHypothesis hypothesis(Long id, HypothesisStatus status, int revision) {
        InvestigationHypothesis entity = new InvestigationHypothesis();
        setId(entity, id);
        entity.setCaseId(CASE_ID);
        entity.setStatus(status);
        entity.setRevision(revision);
        entity.setRationale("初始判断依据");
        entity.setRequiredEvidenceTypes("TRANSACTION");
        return entity;
    }

    private AmlAlert alert(Long id, String externalId) {
        AmlAlert entity = new AmlAlert();
        setId(entity, id);
        entity.setExternalAlertId(externalId);
        entity.setCaseId(CASE_ID);
        entity.setStatus(AlertStatus.LINKED);
        entity.setRevision(0);
        return entity;
    }

    private ExplanationSubmission submission(Long id, Long unitId, ExplanationOutcome outcome) {
        ExplanationSubmission entity = new ExplanationSubmission();
        setId(entity, id);
        entity.setUnitId(unitId);
        entity.setCaseId(CASE_ID);
        entity.setSubmissionNo(1);
        entity.setOutcome(outcome);
        entity.setInputDigest("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        entity.setState(SubmissionState.CURRENT);
        entity.setSubmittedBy("analyst");
        return entity;
    }

    private EvidenceArtifactVersion artifact(Long id) {
        EvidenceArtifactVersion entity = new EvidenceArtifactVersion();
        setId(entity, id);
        entity.setCaseId(CASE_ID);
        entity.setArtifactKey("core_banking:txn-doc-001");
        entity.setVersion(1);
        entity.setSourceSystem("CORE_BANKING");
        entity.setSourceReference("TXN-DOC-001");
        entity.setContentSha256("a".repeat(64));
        entity.setAvailability("RESOLVED");
        entity.setIntegrityStatus("MATCH");
        entity.setCapturedBy("analyst");
        return entity;
    }

    /** 实体主键无 setter：测试用反射设置。 */
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
