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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private final ExplanationIssueReviewRepository issueReviews = mock(ExplanationIssueReviewRepository.class);
    private final com.bank.aml.security.UserAccountRepository userAccounts =
            mock(com.bank.aml.security.UserAccountRepository.class);
    private final DemoEvidenceSourceAdapter evidenceSource = new DemoEvidenceSourceAdapter();
    private final com.bank.aml.datasource.CustomerDataPort customerData =
            mock(com.bank.aml.datasource.CustomerDataPort.class);
    private final AuditOutboxService auditOutbox = mock(AuditOutboxService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final java.util.Map<Long, ExplanationSubmission> submissionById = new java.util.HashMap<>();
    private final java.util.Map<String, ExplanationSubmission> submissionByKey = new java.util.HashMap<>();
    private final java.util.Map<String, EvidenceArtifactVersion> artifactStore = new java.util.HashMap<>();
    private final List<ExplanationSubmission> currentSubmissions = new java.util.ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(1);

    private final ExplanationWorkspaceService service = new ExplanationWorkspaceService(cases, units,
            submissions, issues, bases, artifacts, verifications, evidenceUses,
            issueReviews, userAccounts, coverage, hypotheses,
            alerts, eddRequests, new ExplanationPolicyCatalog(), auditOutbox, evidenceSource,
            customerData, objectMapper, clock);

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
        when(artifacts.save(any())).thenAnswer(inv -> {
            EvidenceArtifactVersion saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, idSeq.getAndIncrement() + 1000);
            }
            artifactStore.put(saved.getCaseId() + "|" + saved.getArtifactKey(), saved);
            return saved;
        });
        when(artifacts.findTopByCaseIdAndArtifactKeyOrderByVersionDesc(any(), any())).thenAnswer(inv -> {
            Long caseId = inv.getArgument(0);
            String key = inv.getArgument(1);
            return Optional.ofNullable(artifactStore.get(caseId + "|" + key));
        });
        when(artifacts.findByIdAndCaseId(1L, CASE_ID)).thenReturn(Optional.of(artifact(1L)));
        // A6-01：SATISFIED 引用的材料必须有核验记录（材料 1 已核验 CONFIRMED）
        when(verifications.findByArtifactVersionIdOrderByEventTimeAsc(1L)).thenReturn(List.of(
                verificationEvent(1L, "CONFIRMED")));
        when(coverage.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(coverage.findByAlertId(11L)).thenReturn(Optional.of(coverageRow));
        when(hypotheses.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hypotheses.findById(31L)).thenReturn(Optional.of(hypothesis));
        when(hypotheses.findByCaseIdOrderByIdAsc(CASE_ID)).thenReturn(List.of(hypothesis));
        when(alerts.findById(11L)).thenReturn(Optional.of(alert(11L, "ALERT-A")));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(CASE_ID)).thenReturn(List.of(alert(11L, "ALERT-A")));
        when(eddRequests.findByCaseIdAndStatusOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenReturn(List.of());
        // 服务器冻结交易源（A5-01）：T-1001/T-2001 为权威来源；其它交易自编 ID 会被拒绝
        when(customerData.transactionsOf("C001")).thenReturn(List.of(
                new com.bank.aml.domain.TransactionRecord(
                        LocalDateTime.parse("2026-09-01T10:15:00"),
                        new java.math.BigDecimal("320000.00"), "转入", "丙集团公司", null,
                        "企业网银", "货款结算", "CNY", "T-1001"),
                new com.bank.aml.domain.TransactionRecord(
                        LocalDateTime.parse("2026-09-02T14:30:00"),
                        new java.math.BigDecimal("120000.00"), "转入", "丙集团公司", null,
                        "企业网银", "货款结算", "CNY", "T-1002"),
                new com.bank.aml.domain.TransactionRecord(
                        LocalDateTime.parse("2026-09-03T09:00:00"),
                        new java.math.BigDecimal("500000.00"), "转入", "供应链B", null,
                        "柜面", "预付款", "CNY", "T-2001")));
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
        // 提交不可变 + 依据使用记录同事务落库（A5-05：引用材料 → evidence_use）
        assertThat(result.submissionId()).isNotNull();
        verify(evidenceUses, times(6)).save(any()); // 六问题各引用 1 份材料
        // 依据版本（verification_basis）已冻结并绑定提交
        verify(bases).save(any());
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
        verify(submissions, atLeastOnce()).save(ArgumentMatchers.argThat(
                sub -> sub instanceof ExplanationSubmission s && s.isFollowupRequired()));
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

    /** 抓取时服务端真实取得内容：来源不存在 → NOT_FOUND，不得伪装 RESOLVED/MATCH（A5-01）。 */
    @Test
    void hashMismatchCapturesIntegrityBlocker() {
        // 服务端抓取已注册夹具 → RESOLVED + 服务端摘要
        ExplanationViews.EvidenceView view = service.captureEvidence(CASE_ID, "CORE_BANKING",
                "TXN-DOC-001", "analyst");
        assertThat(view.availability()).isEqualTo("RESOLVED");
        assertThat(view.contentSha256()).isEqualTo(
                DemoEvidenceSourceAdapter.contentSha256Of(
                        "核心系统交易回单：付款人=丙集团公司；收款人=甲贸易公司；金额=200000.00 CNY；"
                                + "附言=乙制造公司货款（演示夹具数据）"));

        // 来源系统中不存在的记录 → NOT_FOUND，且登记关键问题（不得形成最终决定）
        ExplanationViews.EvidenceView missing = service.captureEvidence(CASE_ID, "CORE_BANKING",
                "NONEXISTENT-RECORD", "analyst");
        assertThat(missing.availability()).isEqualTo("NOT_FOUND");
        verify(issues).save(ArgumentMatchers.argThat(issue -> issue instanceof ExplanationIssue i
                && i.getIssueKey().startsWith("SOURCE_UNAVAILABLE")));
    }

    // ---- V2-12：新材料不关联任何单元 → 进入案件待分派清单（问题），不可“不引用”绕过 ----

    @Test
    void capturedMaterialEntersUnassignedFactsList() {
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-002", "analyst");

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
                "NOT_RELEVANT_WITH_REASON", "该材料与本次判断无关且金额很小", "DOC-REF-1", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能被认定不相关");
        assertThatThrownBy(() -> service.proposeDowngrade(CASE_ID, 9L, 0,
                "CONTEXT_GAP", "不影响本次决定的原因为：材料已被独立来源重新核验并一致", "DOC-REF-2", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许降级");
    }

    /** 重要性降级两步确认（A5-04）：提案人不能确认自己的提案；确认人须为认证 REVIEWER/ADMIN。 */
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
        when(issues.save(any())).thenAnswer(inv -> inv.getArgument(0));
        java.util.Map<Long, ExplanationIssueReview> reviewById = new java.util.HashMap<>();
        when(issueReviews.save(any())).thenAnswer(inv -> {
            ExplanationIssueReview saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, 77L);
            }
            reviewById.put(saved.getId(), saved);
            return saved;
        });
        when(issueReviews.findByCaseIdAndStatusOrderByIdAsc(CASE_ID, "PENDING")).thenReturn(List.of());
        when(issueReviews.findByIdAndCaseId(any(), ArgumentMatchers.eq(CASE_ID))).thenAnswer(
                inv -> Optional.ofNullable(reviewById.get(inv.getArgument(0, Long.class))));
        com.bank.aml.security.UserAccount reviewerAccount = new com.bank.aml.security.UserAccount();
        reviewerAccount.setUsername("reviewer-b");
        reviewerAccount.setRole("REVIEWER");
        reviewerAccount.setEnabled(true);
        when(userAccounts.findByUsername("reviewer-b")).thenReturn(Optional.of(reviewerAccount));
        // 提案人账号（无 REVIEWER 角色）不能充当确认人
        com.bank.aml.security.UserAccount analystAccount = new com.bank.aml.security.UserAccount();
        analystAccount.setUsername("analyst");
        analystAccount.setRole("ANALYST");
        analystAccount.setEnabled(true);
        when(userAccounts.findByUsername("analyst")).thenReturn(Optional.of(analystAccount));
        when(userAccounts.findByUsername("nonexistent-reviewer-999")).thenReturn(Optional.empty());

        ExplanationViews.IssueReviewView proposal = service.proposeDowngrade(CASE_ID, 10L, 0,
                "CONTEXT_GAP",
                "不影响本次决定的理由与引用已完整记录：该缺口仅涉及非命中交易的辅助说明", "DOC-REF-3", "analyst");
        assertThat(proposal.status()).isEqualTo("PENDING");
        // 问题现状不变，等级仍为 DECISION_CRITICAL
        assertThat(gap.getSeverity()).isEqualTo(IssueSeverity.DECISION_CRITICAL);

        // 提案人自己不能确认：analyst 账户无 REVIEWER/ADMIN 角色，被身份校验拒绝（A5-04）
        assertThatThrownBy(() -> service.confirmDowngrade(CASE_ID, 77L, 0, 0, null, "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REVIEWER/ADMIN");
        // 伪造的确认人（不存在的账户）不能确认
        assertThatThrownBy(() -> service.confirmDowngrade(CASE_ID, 77L, 0, 0, null, "nonexistent-reviewer-999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REVIEWER/ADMIN");

        service.confirmDowngrade(CASE_ID, 77L, 0, 0, null, "reviewer-b");
        assertThat(gap.getSeverity()).isEqualTo(IssueSeverity.CONTEXT_GAP);
        // 确认身份由服务端从认证上下文写入（A5-04）
        assertThat(gap.getConfirmedBy()).isEqualTo("reviewer-b");
    }

    /** 降级提案人（即使具备 REVIEWER 角色）不能确认自己的提案（A5-04 双人确认）。 */
    @Test
    void downgradeProposerCannotSelfConfirm() {
        ExplanationIssue gap = new ExplanationIssue();
        setId(gap, 11L);
        gap.setCaseId(CASE_ID);
        gap.setIssueKey("CONTEXT_GAP:2");
        gap.setSeverity(IssueSeverity.DECISION_CRITICAL);
        gap.setDisposition(IssueDisposition.OPEN);
        gap.setRevision(0);
        when(issues.findByIdAndCaseId(11L, CASE_ID)).thenReturn(Optional.of(gap));
        when(issues.save(any())).thenAnswer(inv -> inv.getArgument(0));
        java.util.Map<Long, ExplanationIssueReview> reviewById = new java.util.HashMap<>();
        when(issueReviews.save(any())).thenAnswer(inv -> {
            ExplanationIssueReview saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, 78L);
            }
            reviewById.put(saved.getId(), saved);
            return saved;
        });
        when(issueReviews.findByCaseIdAndStatusOrderByIdAsc(CASE_ID, "PENDING")).thenReturn(List.of());
        when(issueReviews.findByIdAndCaseId(any(), ArgumentMatchers.eq(CASE_ID))).thenAnswer(
                inv -> Optional.ofNullable(reviewById.get(inv.getArgument(0, Long.class))));
        com.bank.aml.security.UserAccount proposer = new com.bank.aml.security.UserAccount();
        proposer.setUsername("reviewer-a");
        proposer.setRole("REVIEWER");
        proposer.setEnabled(true);
        when(userAccounts.findByUsername("reviewer-a")).thenReturn(Optional.of(proposer));
        com.bank.aml.security.UserAccount confirmer = new com.bank.aml.security.UserAccount();
        confirmer.setUsername("reviewer-b");
        confirmer.setRole("REVIEWER");
        confirmer.setEnabled(true);
        when(userAccounts.findByUsername("reviewer-b")).thenReturn(Optional.of(confirmer));

        service.proposeDowngrade(CASE_ID, 11L, 0, "CONTEXT_GAP",
                "降低等级的理由：该缺口已由独立来源核验并支持当前判断", "DOC-REF-4", "reviewer-a");
        // 提案人不能确认自己的提案
        assertThatThrownBy(() -> service.confirmDowngrade(CASE_ID, 78L, 0, 0, null, "reviewer-a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("双人确认");
        // 不同复核人可以确认
        service.confirmDowngrade(CASE_ID, 78L, 0, 0, null, "reviewer-b");
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

    // ================ S2 验收防回归（A5-06：Clock 即时重评 + TP-22 token 顺序） ================

    /** TP-25/A5-06：预付交期已过 + 无任何任务 → 最终排除在最终事务内被 Clock 重评阻断。 */
    @Test
    void prepayExpiryBlocksFinalExclusionEvenWithoutTasks() throws Exception {
        saveDraft(prepayDraft("2026-09-11"), 0);
        var submitted = service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "A5-06", "analyst");
        assertThat(submissionById.get(submitted.submissionId()).isFollowupRequired()).isTrue();
        // Clock 推进到交期之后；最终复核时无任何 EDD 任务存在
        Clock afterDue = Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZONE);
        // 记录 save 过的 issue，evaluateReadiness 能看到重评恢复的关键问题（内存模拟）
        java.util.List<ExplanationIssue> savedIssues = new java.util.ArrayList<>();
        org.mockito.Mockito.reset(issues);
        when(issues.save(any())).thenAnswer(inv -> {
            ExplanationIssue saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, idSeq.getAndIncrement() + 3000);
            }
            savedIssues.add(saved);
            return saved;
        });
        when(issues.findByCaseIdAndIssueKey(any(), any())).thenAnswer(inv -> savedIssues.stream()
                .filter(issue -> issue.getIssueKey().equals(inv.getArgument(1))).findFirst());
        when(issues.findByCaseIdOrderByIdAsc(CASE_ID)).thenAnswer(inv -> List.copyOf(savedIssues));
        when(issues.findByCaseIdAndUnitIdOrderByIdAsc(any(), any())).thenReturn(List.of());
        when(issues.findByIdAndCaseId(any(), ArgumentMatchers.eq(CASE_ID))).thenAnswer(inv ->
                Optional.ofNullable(inv.getArgument(0) instanceof ExplanationIssue i ? i : null));
        var laterService = new ExplanationWorkspaceService(cases, units, submissions, issues, bases,
                artifacts, verifications, evidenceUses, issueReviews, userAccounts, coverage, hypotheses,
                alerts, eddRequests, new ExplanationPolicyCatalog(), auditOutbox, evidenceSource,
                customerData, objectMapper, afterDue);

        assertThatThrownBy(() -> laterService.validateReadyForReview(caseEntity,
                com.bank.aml.review.ReviewDecision.EXCLUDE_FALSE_POSITIVE,
                "reviewer-b", laterService.reviewBasisToken(CASE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("最终处置条件");
        // 交期重评恢复的关键问题已登记（DELIVERY_OVERDUE）
        assertThat(savedIssues.stream().anyMatch(issue ->
                issue.getIssueKey().startsWith("DELIVERY_OVERDUE"))).isTrue();
    }

    /** TP-22/A5-06 语义：交期未过 → 最终排除不被阻断（Clock 即时评估通过）。 */
    @Test
    void prepayNotYetDuePassesFinalExclusionWithClock() {
        saveDraft(prepayDraft("2026-12-01"), 0);
        service.submitUnit(CASE_ID, 100L, 1, service.reviewBasisToken(CASE_ID), "A5-06-OK", "analyst");
        // A6-03/RC-07：未到期 ≠ 已安排——无承接任务时最终排除被阻断
        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity,
                com.bank.aml.review.ReviewDecision.EXCLUDE_FALSE_POSITIVE,
                "reviewer-b", service.reviewBasisToken(CASE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未到期不等于已安排");
        // 有合格承接任务（OPEN CONTINUING_REVIEW + 有效承办人 + 未来期限 + 完成标准）→ 排除可行
        com.bank.aml.security.UserAccount assignee = new com.bank.aml.security.UserAccount();
        assignee.setUsername("analyst");
        assignee.setRole("ANALYST");
        assignee.setEnabled(true);
        when(userAccounts.findByUsername("analyst")).thenReturn(Optional.of(assignee));
        EnhancedDueDiligenceRequest continuing = new EnhancedDueDiligenceRequest();
        setId(continuing, 66L);
        continuing.setCaseId(CASE_ID);
        continuing.setRoundNo(2);
        continuing.setReasonCode("CONTINUING_REVIEW");
        continuing.setRequiredItemsJson("[\"TRANSACTION_PURPOSE\"]");
        continuing.setRequestedBy("reviewer-b");
        continuing.setRequestedAt(java.time.LocalDateTime.now(clock));
        continuing.setAssignedTo("analyst");
        continuing.setAssignedUnit("调查一组");
        continuing.setDueAt(java.time.LocalDateTime.now(clock).plusDays(30));
        continuing.setStatus(EnhancedDueDiligenceStatus.OPEN);
        continuing.setPurpose(EddTaskPurpose.CONTINUING_REVIEW);
        continuing.setCompletionStandard("2026-12-01 交付核验：核对签收记录与入账，观察与限制已记录");
        when(eddRequests.findByCaseIdAndStatusOrderByIdAsc(ArgumentMatchers.eq(CASE_ID), any()))
                .thenReturn(List.of(continuing));
        service.validateReadyForReview(caseEntity,
                com.bank.aml.review.ReviewDecision.EXCLUDE_FALSE_POSITIVE,
                "reviewer-b", service.reviewBasisToken(CASE_ID));
    }

    /** A5-07：变更前令牌校验入口（validateReviewBasisToken）在令牌失效时拒绝。 */
    @Test
    void reviewBasisTokenValidatedBeforeAnyMutation() {
        assertThatThrownBy(() -> service.validateReviewBasisToken(CASE_ID, "stale-token"))
                .isInstanceOf(InvestigationRevisionConflictException.class)
                .hasMessageContaining("令牌已失效");
        service.validateReviewBasisToken(CASE_ID, service.reviewBasisToken(CASE_ID));
    }

    // ================ S1 验收防回归（A5-01/05，TP-04/TP-17） ================

    /** TP-04：自编交易 ID + 金额自洽 → 拒绝（不属于服务器冻结来源集合）。 */
    @Test
    void inventedTransactionIsRejectedByServerScope() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = explainedSettlementDraft(mapper);
        ObjectNode scope = (ObjectNode) draft.path("scope");
        scope.putArray("reviewedTransactionIds").add("NONEXISTENT-TRANSACTION");
        scope.putObject("transactionAmounts").put("NONEXISTENT-TRANSACTION", "1.00");
        scope.putArray("allocations").addObject().put("transactionId", "NONEXISTENT-TRANSACTION")
                .put("amount", "1.00");
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "TP-04", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于服务器冻结的交易来源集合");
    }

    /** 声明金额与服务器来源金额不一致 → 拒绝（以来源金额为准）。 */
    @Test
    void declaredAmountMismatchingSourceIsRejected() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = explainedSettlementDraft(mapper);
        ((ObjectNode) draft.path("scope").path("transactionAmounts")).put("T-1001", "999.00");
        ArrayNode allocations = (ArrayNode) draft.path("scope").path("allocations");
        allocations.removeAll();
        allocations.addObject().put("transactionId", "T-1001").put("amount", "999.00");
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "AMT-1", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("服务器来源金额");
    }

    /** TP-17：同源同内容再次抓取 → 幂等返回既有版本；夹具内容变化后 → 追加新版本且旧版本可回放。 */
    @Test
    void sameSourceSameContentIsIdempotentAndChangedContentAppendsVersion() {
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");
        var first = artifacts.findTopByCaseIdAndArtifactKeyOrderByVersionDesc(CASE_ID,
                "core_banking:txn-doc-001");
        assertThat(first).isPresent();
        assertThat(first.get().getVersion()).isEqualTo(1);

        // 同内容再次抓取：幂等
        service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");
        assertThat(artifacts.findTopByCaseIdAndArtifactKeyOrderByVersionDesc(CASE_ID,
                "core_banking:txn-doc-001").get().getVersion()).isEqualTo(1);

        // 夹具内容变化（模拟来源内容被替换）→ 新版本 v2
        evidenceSource.putFixture("CORE_BANKING", "TXN-DOC-001", "被替换后的内容（演示）");
        var changed = service.captureEvidence(CASE_ID, "CORE_BANKING", "TXN-DOC-001", "analyst");
        assertThat(changed.version()).isEqualTo(2);
        assertThat(changed.contentSha256()).isEqualTo(
                DemoEvidenceSourceAdapter.contentSha256Of("被替换后的内容（演示）"));
        // 旧版本仍可回放
        verify(artifacts, times(2)).save(any());
    }

    /** A5-05：采用材料被 supersede → 通过 explanation_evidence_use 反查使提交 STALE。 */
    @Test
    void supersedingUsedArtifactStalesSubmissionViaReverseIndex() {
        saveDraft(explainedSettlementDraft(), 0);
        var submitted = service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "A5-05", "analyst");
        // 提交已写反向引用（六问题 × artifactVersionIds=1）
        verify(evidenceUses, times(6)).save(any());
        // 反查表按实际写入返回（内存模拟）：提交阶段已写入 6 条 use（artifactVersionId=1）
        java.util.List<ExplanationEvidenceUse> savedUses = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ExplanationEvidenceUse use = new ExplanationEvidenceUse();
            use.setSubmissionId(submissionById.values().iterator().next().getId());
            use.setCaseId(CASE_ID);
            use.setQuestionCode("Q" + (i + 1));
            use.setArtifactVersionId(1L);
            savedUses.add(use);
        }
        org.mockito.Mockito.reset(evidenceUses);
        java.util.Map<Long, ExplanationSubmission> subMap = submissionById;
        when(evidenceUses.save(any())).thenAnswer(inv -> {
            ExplanationEvidenceUse use = inv.getArgument(0);
            if (use.getId() == null) {
                setId(use, idSeq.getAndIncrement() + 2000);
            }
            savedUses.add(use);
            return use;
        });
        when(evidenceUses.findByCaseIdAndArtifactVersionId(any(), any())).thenAnswer(inv -> {
            Long artifactVersionId = inv.getArgument(1);
            return savedUses.stream().filter(use -> artifactVersionId.equals(use.getArtifactVersionId()))
                    .toList();
        });
        when(evidenceUses.findByCaseIdAndArtifactVersionId(CASE_ID, 5L)).thenReturn(List.of());
        // supersede 材料 1：反查 → 提交 STALE
        service.markArtifactSuperseded(CASE_ID, 1L, "source changed", "analyst");
        assertThat(subMap.get(submitted.submissionId()).getState())
                .isEqualTo(SubmissionState.STALE);
        assertThat(unit.getCurrentSubmissionId()).isNull();
        assertThat(coverageRow.getConclusion()).isEqualTo(AlertCoverageConclusion.PENDING);
    }

    // ================ S0 验收防回归（A5-01～A5-04，替代 .tmp 特征探针） ================

    /** A5-02：NOT_SATISFIED 不能提交 EXPLAINED；提交阶段即拒绝（不自动变 SUSPICIOUS）。 */
    @Test
    void notSatisfiedQuestionBlocksExplainedSubmission() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = explainedSettlementDraft(mapper);
        ((ObjectNode) draft.path("questions").path("Q3")).put("assessment", "NOT_SATISFIED");
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "A5-02", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOT_SATISFIED")
                .hasMessageContaining("EXPLAINED");
        assertThat(coverageRow.getConclusion()).isEqualTo(AlertCoverageConclusion.PENDING);
    }

    /** A5-03：案件级待分派关键事实（unitId=null 的 DECISION_CRITICAL）阻断最终排除。 */
    @Test
    void caseLevelUnassignedCriticalFactBlocksFinalExclusion() {
        saveDraft(explainedSettlementDraft(), 0);
        service.submitUnit(CASE_ID, 100L, 1, service.reviewBasisToken(CASE_ID), "A5-03", "analyst");
        ExplanationIssue unassigned = new ExplanationIssue();
        setId(unassigned, 99L);
        unassigned.setCaseId(CASE_ID);
        unassigned.setIssueKey("FACT_UNASSIGNED:new-counter-evidence:v1");
        unassigned.setSeverity(IssueSeverity.DECISION_CRITICAL);
        unassigned.setDisposition(IssueDisposition.OPEN);
        when(issues.findByCaseIdOrderByIdAsc(CASE_ID)).thenReturn(List.of(unassigned));
        caseEntity.setCaseFactsEpoch(caseEntity.getCaseFactsEpoch());

        assertThatThrownBy(() -> service.validateReadyForReview(caseEntity,
                com.bank.aml.review.ReviewDecision.EXCLUDE_FALSE_POSITIVE,
                "reviewer-b", service.reviewBasisToken(CASE_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待分派");
    }

    /** A5-04：分析员编造的 confirmedBy 无效——降级已改为提案/确认两步，处置接口不再接受降级参数。 */
    @Test
    void arbitraryConfirmerCannotDowngradeViaDisposition() {
        ExplanationIssue issue = new ExplanationIssue();
        setId(issue, 99L);
        issue.setCaseId(CASE_ID);
        issue.setIssueKey("MISSING_SOURCE");
        issue.setSeverity(IssueSeverity.DECISION_CRITICAL);
        issue.setDisposition(IssueDisposition.OPEN);
        issue.setRevision(0);
        when(issues.findByIdAndCaseId(99L, CASE_ID)).thenReturn(Optional.of(issue));
        java.util.Map<Long, ExplanationIssueReview> reviewById = new java.util.HashMap<>();
        when(issueReviews.save(any())).thenAnswer(inv -> {
            ExplanationIssueReview saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, 90L);
            }
            reviewById.put(saved.getId(), saved);
            return saved;
        });
        when(issueReviews.findByCaseIdAndStatusOrderByIdAsc(CASE_ID, "PENDING")).thenReturn(List.of());
        when(issueReviews.findByIdAndCaseId(any(), ArgumentMatchers.eq(CASE_ID))).thenAnswer(
                inv -> Optional.ofNullable(reviewById.get(inv.getArgument(0, Long.class))));

        // 处置接口不再有降级参数（旧签名已移除）；伪造复核人降级必须走两步流程且被身份校验拒绝：
        // 提案可以被创建，但确认时编造的 REVIEWER/ADMIN 身份无法通过账户校验。
        when(userAccounts.findByUsername("nonexistent-reviewer-999")).thenReturn(Optional.empty());
        service.proposeDowngrade(CASE_ID, 99L, 0, "CONTEXT_GAP",
                "该问题对本次决定无实质影响，理由已完整记录", null, "analyst");
        assertThatThrownBy(() -> service.confirmDowngrade(CASE_ID, 90L, 0, 0, null, "nonexistent-reviewer-999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REVIEWER/ADMIN");
        assertThat(issue.getSeverity()).isEqualTo(IssueSeverity.DECISION_CRITICAL);
    }

    // ================ S3 验收防回归（集团代付配方，TP-06/TP-09/TP-10/TP-11/TP-12） ================

    /** 构造贯穿样例的集团代付草稿（甲收款、丙代付、乙买方、授权 AU-01）。 */
    private ObjectNode groupPaymentDraft(ObjectMapper mapper, String c3Status, String c4Status,
                                         String outcome, String authorityLimit,
                                         java.util.List<String> coveredTxs) throws Exception {
        ObjectNode draft = mapper.createObjectNode();
        ObjectNode policy = draft.putObject("policy");
        policy.put("businessRole", "境内贸易企业：自营商品采购与销售，销售货款由买方集团统一代付");
        policy.put("paymentStage", "DELIVERED_SETTLEMENT");
        policy.put("payerMatchesContractBuyer", false);
        policy.put("payeeMatchesContractSeller", true);
        policy.put("groupRelationshipStatus", "GROUP_RELATIONSHIP_CONFIRMED");
        ObjectNode scope = draft.putObject("scope");
        ArrayNode reviewed = scope.putArray("reviewedTransactionIds");
        reviewed.add("T-1001");
        reviewed.add("T-1002");
        scope.put("scopeEnumerationNote", "以监测系统 9 月冻结命中清单为准，逐笔核对交易流水后枚举");
        ObjectNode amounts = scope.putObject("transactionAmounts");
        amounts.put("T-1001", "320000.00");
        amounts.put("T-1002", "120000.00");
        ArrayNode allocations = scope.putArray("allocations");
        allocations.addObject().put("transactionId", "T-1001").put("orderRef", "SO-01")
                .put("amount", "320000.00");
        allocations.addObject().put("transactionId", "T-1002").put("orderRef", "SO-02")
                .put("amount", "120000.00");
        ObjectNode claims = draft.putObject("claims");
        claims.putObject("C1").put("status", "SUPPORTED")
                .put("judgement", "核心流水付款账户归属丙集团公司，KYC 档案确认乙丙同属一集团");
        claims.putObject("C2").put("status", "SUPPORTED")
                .put("judgement", "订单 SO-01/SO-02 与交付签收记录对应乙对甲的货款义务");
        claims.putObject("C3").put("status", c3Status)
                .put("judgement", "授权 AU-01 覆盖 SO-01/SO-02，额度与有效期已核对");
        claims.putObject("C4").put("status", c4Status)
                .put("judgement", "两笔收款在授权范围内履行乙的付款义务，逐笔分配一致");
        ObjectNode authority = draft.putObject("authority");
        if (authorityLimit != null) {
            authority.put("authorityRef", "AU-01");
            authority.put("limitAmount", authorityLimit);
            ArrayNode covered = authority.putArray("coveredTransactionIds");
            coveredTxs.forEach(covered::add);
        }
        ObjectNode questions = draft.putObject("questions");
        for (String code : new String[]{"Q1", "Q2", "Q3", "Q4", "Q5", "Q6"}) {
            ObjectNode question = questions.putObject(code);
            question.put("assessment", "SATISFIED");
            question.put("judgement", "代付链路核验：" + code + " 对应事实已由授权与流水核对支持");
            question.put("factLocation", "CORE_BANKING 流水 + 授权 AU-01");
            question.put("factKind", "OBSERVED_FACT");
            question.put("verificationMethod", "INDEPENDENT_SOURCE_CHECK");
            question.put("limitations", "核验仅覆盖冻结来源集合内的材料");
            question.putArray("artifactVersionIds").add(1L);
            question.putArray("transactionIds").add("T-1001");
        }
        draft.put("outcome", outcome);
        return draft;
    }

    /** TP-09：集团关系成立但 C3 授权状态 UNASSESSED → 不能 EXPLAINED。 */
    @Test
    void groupPaymentWithoutAssessedAuthorityBlocksExplained() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = groupPaymentDraft(mapper, "UNASSESSED", "SUPPORTED", "EXPLAINED", null, null);
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "TP-09", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C3")
                .hasMessageContaining("EXPLAINED");
    }

    /** TP-10：授权额度 200,000 < 已覆盖交易 320,000 → 缺口问题登记；不得整笔解释成立。 */
    @Test
    void authorityLimitGapIsRegisteredAndBlocksExplained() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = groupPaymentDraft(mapper, "SUPPORTED", "SUPPORTED", "EXPLAINED",
                "200000.00", List.of("T-1001"));
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        // A6-02/RC-05：授权只覆盖 T-1001（320,000），T-1002（120,000）缺口稳定阻断；
        // 缺口先于额度比较暴露——不能靠缩小覆盖集合隐去未授权金额。
        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "TP-10", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("T-1002")
                .hasMessageContaining("未被授权覆盖");
    }

    /** TP-10 额度语义：全覆盖集合但授权额度不足 → 超出部分缺口阻断。 */
    @Test
    void authorityLimitExceededEvenWithFullCoverage() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = groupPaymentDraft(mapper, "SUPPORTED", "SUPPORTED", "EXPLAINED",
                "400000.00", List.of("T-1001", "T-1002"));
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "TP-10-LIMIT", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("低于已覆盖交易合计")
                .hasMessageContaining("不得整笔解释成立");
    }

    /** TP-11：C4 UNRESOLVED（第二笔未被授权覆盖）→ 不得 EXPLAINED；缺口登记。 */
    @Test
    void partialCoverageKeepsGapAndBlocksExplained() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = groupPaymentDraft(mapper, "SUPPORTED", "UNRESOLVED", "EXPLAINED", null, null);
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "TP-11", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C4");
        verify(issues).save(ArgumentMatchers.argThat(issue -> issue instanceof ExplanationIssue i
                && i.getIssueKey().startsWith("GROUP_EXECUTION")));
    }

    /** TP-12：C3 CONTRADICTED（授权被撤销）→ 不得 EXPLAINED；矛盾保留人工判断。 */
    @Test
    void contradictedAuthorityBlocksExplained() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = groupPaymentDraft(mapper, "CONTRADICTED", "SUPPORTED", "EXPLAINED", null, null);
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "TP-12", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C3");
    }

    /** TP-06：C3=UNRESOLVED 时提交 SUSPICIOUS → 允许（不自动确认可疑）；缺口问题登记。 */
    @Test
    void unresolvedAuthorityAllowsSuspiciousWithBasis() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = groupPaymentDraft(mapper, "UNRESOLVED", "SUPPORTED", "SUSPICIOUS", null, null);
        ObjectNode suspicion = draft.putObject("suspicionBasis");
        suspicion.put("assessedFacts", "授权 AU-01 的覆盖范围与第二笔交易矛盾（核验观察已记录）");
        suspicion.put("reverseExplanations", "客户解释为额度追加，但无追加授权的独立来源");
        suspicion.put("whyInsufficient", "追加授权无法核实，120,000 元收款性质仍不能排除可疑");
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        ExplanationViews.SubmissionResult result = service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "TP-06", "analyst");
        assertThat(result.outcome()).isEqualTo(ExplanationOutcome.SUSPICIOUS);
        verify(issues).save(ArgumentMatchers.argThat(issue -> issue instanceof ExplanationIssue i
                && i.getIssueKey().startsWith("GROUP_AUTHORITY")));
    }

    /** 买方否认集团关系（GROUP_RELATIONSHIP_DENIED）→ 政策不适用，不切宽松配方。 */
    @Test
    void deniedGroupRelationshipIsPolicyNotApplicable() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draft = groupPaymentDraft(mapper, "SUPPORTED", "SUPPORTED", "EXPLAINED", null, null);
        ((ObjectNode) draft.path("policy")).put("groupRelationshipStatus", "GROUP_RELATIONSHIP_DENIED");
        unit.setDraftJson(mapper.writeValueAsString(draft));
        unit.setDraftRevision(1);

        assertThatThrownBy(() -> service.submitUnit(CASE_ID, 100L, 1,
                service.reviewBasisToken(CASE_ID), "TP-DENIED", "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ExplanationPolicyCatalog.POLICY_NOT_APPLICABLE);
    }

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
        entity.setCustomerId("C001");
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

    /** A6-01：SATISFIED 引用的材料必须有核验记录。 */
    private EvidenceVerificationEvent verificationEvent(Long artifactVersionId, String result) {
        EvidenceVerificationEvent event = new EvidenceVerificationEvent();
        setId(event, idSeq.incrementAndGet() + 9000);
        event.setCaseId(CASE_ID);
        event.setArtifactVersionId(artifactVersionId);
        event.setMethod("INDEPENDENT_SOURCE_CHECK");
        event.setObservedFacts("来源内容与业务事实核对一致，观察与限制已记录");
        event.setResult(result);
        event.setActor("verifier-x");
        event.setEventTime(java.time.LocalDateTime.now(clock));
        return event;
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
