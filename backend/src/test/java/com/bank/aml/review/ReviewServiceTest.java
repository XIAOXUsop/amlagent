package com.bank.aml.review;

import com.bank.aml.TestClocks;
import com.bank.aml.application.ReviewService;
import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.EddTaskPurpose;
import com.bank.aml.domain.ReviewDecision;
import com.bank.aml.explanation.ExplanationWorkspaceService;
import com.bank.aml.investigation.InvestigationService;
import com.bank.aml.reporting.SuspiciousTransactionReportService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private final CaseRepository caseRepository = mock(CaseRepository.class);

    private final ManualReviewRepository reviewRepository = mock(ManualReviewRepository.class);

    private final EnhancedDueDiligenceService eddService = mock(EnhancedDueDiligenceService.class);

    private final SuspiciousTransactionReportService reportService = mock(SuspiciousTransactionReportService.class);

    private final AuditOutboxService auditOutbox = mock(AuditOutboxService.class);

    private final InvestigationService investigationService = mock(InvestigationService.class);

    private final ReviewService service = new ReviewService(caseRepository, reviewRepository, eddService, reportService,
            auditOutbox, investigationService, mock(ExplanationWorkspaceService.class), TestClocks.FIXED);

    @Test
    void submitThrowsConflictWhenNotHold() {
        CaseEntity done = new CaseEntity();
        done.setStatus(CaseStatus.DONE);
        when(caseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.submit(1L, "reviewer", "高风险", "CONFIRM_SUSPICIOUS",
                "TRANSACTION_PATTERN_INCONSISTENT", "已核验交易流水并确认模式异常", 0, null, null, null, null))
            .isInstanceOf(WorkflowStateConflictException.class);
    }

    @Test
    void submitThrowsConflictWhenStaleRevision() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        when(caseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        // 旧 revision：条件更新返回 0，并发下已被其他复核员处理
        when(caseRepository.completeReview(eq(1L), eq(CaseStatus.REPORT_PENDING), eq(CaseStatus.HOLD), eq(0),
                eq("CONFIRM_SUSPICIOUS"), eq("TRANSACTION_PATTERN_INCONSISTENT"), any()))
            .thenReturn(0);

        assertThatThrownBy(() -> service.submit(1L, "reviewer", "高风险", "CONFIRM_SUSPICIOUS",
                "TRANSACTION_PATTERN_INCONSISTENT", "已核验交易流水并确认模式异常", 0, null, null, null, null))
            .isInstanceOf(WorkflowStateConflictException.class);
    }

    @Test
    void submitThrowsConflictOnIllegalDecision() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        when(caseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.submit(1L, "reviewer", "高风险", "UNKNOWN", "TRANSACTION_PATTERN_INCONSISTENT",
                "已核验交易流水并确认模式异常", 0, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finalDecisionPropagatesInvestigationGateFailureBeforeCaseUpdate() {
        CaseEntity hold = holdCase();
        when(caseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        doThrow(new IllegalStateException("调查尚未满足最终处置条件：仍有预警未形成覆盖结论")).when(investigationService)
            .validateReadyForReview(hold, ReviewDecision.CONFIRM_SUSPICIOUS);

        assertThatThrownBy(() -> service.submit(1L, "reviewer", "高风险", "CONFIRM_SUSPICIOUS",
                "TRANSACTION_PATTERN_INCONSISTENT", "已复核但调查链仍未闭环，不能结案", 0, null, null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("预警未形成覆盖结论");
    }

    @Test
    void confirmSuspiciousCompletesCaseAndPersistsReasonCode() {
        CaseEntity hold = holdCase();
        when(caseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(caseRepository.completeReview(eq(1L), eq(CaseStatus.REPORT_PENDING), eq(CaseStatus.HOLD), eq(0),
                eq("CONFIRM_SUSPICIOUS"), eq("SANCTIONS_OR_WATCHLIST_MATCH"), any()))
            .thenReturn(1);
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManualReview result = service.submit(1L, "reviewer", "高风险", "CONFIRM_SUSPICIOUS",
                "SANCTIONS_OR_WATCHLIST_MATCH", "身份要素核验后确认名单命中", 0, null, null, null, null);

        assertThat(result.getDecision()).isEqualTo("CONFIRM_SUSPICIOUS");
        assertThat(result.getReasonCode()).isEqualTo("SANCTIONS_OR_WATCHLIST_MATCH");
        assertThat(result.getCaseStatusAfter()).isEqualTo("REPORT_PENDING");
        verify(reportService).openPending(1L, result);
    }

    @Test
    void excludeFalsePositiveCompletesCaseWithoutUsingFailureState() {
        CaseEntity hold = holdCase();
        when(caseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(caseRepository.completeReview(eq(1L), eq(CaseStatus.DONE), eq(CaseStatus.HOLD), eq(0),
                eq("EXCLUDE_FALSE_POSITIVE"), eq("VERIFIED_LEGITIMATE_PURPOSE"), any()))
            .thenReturn(1);
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManualReview result = service.submit(1L, "reviewer", "低风险", "EXCLUDE_FALSE_POSITIVE",
                "VERIFIED_LEGITIMATE_PURPOSE", "已核验合同发票及交易对手，业务目的合理", 0, null, null, null, null);

        assertThat(result.getCaseStatusAfter()).isEqualTo("DONE");
        assertThat(result.getReasonCode()).isEqualTo("VERIFIED_LEGITIMATE_PURPOSE");
    }

    @Test
    void requestEnhancedDueDiligenceKeepsCaseOnHold() {
        CaseEntity hold = holdCase();
        when(caseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(caseRepository.requestEnhancedDueDiligence(eq(1L), eq(CaseStatus.HOLD), eq(0),
                eq("REQUEST_ENHANCED_DUE_DILIGENCE"), eq("SOURCE_OF_FUNDS_EVIDENCE_REQUIRED"), any()))
            .thenReturn(1);
        when(reviewRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ManualReview result = service.submit(1L, "reviewer", "高风险", "REQUEST_ENHANCED_DUE_DILIGENCE",
                "SOURCE_OF_FUNDS_EVIDENCE_REQUIRED", "现有材料无法验证资金来源，需要客户补充证明", 0, List.of("SOURCE_OF_FUNDS"),
                LocalDateTime.now(TestClocks.FIXED).plusDays(5), "analyst", "反洗钱分析组");

        assertThat(result.getCaseStatusAfter()).isEqualTo("HOLD");
        verify(caseRepository).requestEnhancedDueDiligence(eq(1L), eq(CaseStatus.HOLD), eq(0),
                eq("REQUEST_ENHANCED_DUE_DILIGENCE"), eq("SOURCE_OF_FUNDS_EVIDENCE_REQUIRED"), any());
        // v2 起使用带接续参数的重载（v1 案件接续为 null）
        verify(eddService).applyReviewDecision(eq(1L), eq(ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE),
                eq(ReviewReasonCode.SOURCE_OF_FUNDS_EVIDENCE_REQUIRED), eq(List.of("SOURCE_OF_FUNDS")), any(),
                eq("analyst"), eq("反洗钱分析组"), eq("reviewer"), any(), eq(null), eq(null));
    }

    @Test
    void rejectsMismatchedReasonAndShortAnalysis() {
        assertThatThrownBy(() -> service.submit(1L, "reviewer", "高风险", "CONFIRM_SUSPICIOUS",
                "VERIFIED_LEGITIMATE_PURPOSE", "已完成详细交易核验记录", 0, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不匹配");

        assertThatThrownBy(() -> service.submit(1L, "reviewer", "高风险", "CONFIRM_SUSPICIOUS",
                "TRANSACTION_PATTERN_INCONSISTENT", "证据不足", 0, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("至少 10 个字符");
    }

    private CaseEntity holdCase() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        hold.setRiskLevel("高风险");
        hold.setRawRiskLevel("中风险");
        return hold;
    }
    // ---- 复核预检（v3 闭环方案 §6 / RC-08 前置）：只读模拟，暴露计划覆盖差异 ----

    @Test
    void precheckExposesPlanCoverageGapsAndTokenStaleness() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        hold.setInvestigationContractVersion(2);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(hold));
        ExplanationWorkspaceService workspace = mock(ExplanationWorkspaceService.class);
        when(workspace.reviewBasisToken(1L)).thenReturn("current-token");
        var readiness = new ExplanationWorkspaceService.InvestigationReadinessResult(true, List.of(), List.of(),
                List.of(), true, false, true);
        when(workspace.readinessResultForReviewer(eq(1L), any())).thenReturn(readiness);
        ReviewService precheckService = new ReviewService(caseRepository, reviewRepository, eddService, reportService,
                auditOutbox, investigationService, workspace, TestClocks.FIXED);
        // EDD：一个 OPEN 决策支持任务（id=22），预检计划只引用 999 → 22 未被覆盖
        EnhancedDueDiligenceRequest openTask = new EnhancedDueDiligenceRequest();
        try {
            var idField = EnhancedDueDiligenceRequest.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(openTask, 22L);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        openTask.setCaseId(1L);
        openTask.setPurpose(EddTaskPurpose.DECISION_SUPPORT);
        openTask.setStatus(EnhancedDueDiligenceStatus.OPEN);
        when(eddService.lookupTask(1L, 999L)).thenReturn(Optional.empty());
        when(eddService.openTasks(1L)).thenReturn(List.of(openTask));

        List<EnhancedDueDiligenceService.ContinuationTaskPlan> plans = List
            .of(new EnhancedDueDiligenceService.ContinuationTaskPlan(999L, "analyst", "team-a",
                    LocalDateTime.now(TestClocks.FIXED).plusDays(3), List.of("TRANSACTION_PURPOSE"), "核验第一项义务的完整记录",
                    null));

        ReviewService.ReviewPrecheckResult result = precheckService.reviewPrecheck(1L, "reviewer-b",
                ReviewDecision.CONFIRM_SUSPICIOUS, "stale-token", plans);

        // token 过期被暴露（而非抛异常）
        assertThat(result.tokenCurrent()).isFalse();
        // 计划引用的原任务不存在 → 逐项差异
        assertThat(result.continuationPlanChecks()).hasSize(1);
        // 未被覆盖的 OPEN 决策支持任务（22）被点名
        assertThat(result.uncoveredDecisionSupportTasks()).containsExactly(22L);
    }

    @Test
    void precheckDoesNotMutateAnything() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        hold.setInvestigationContractVersion(2);
        when(caseRepository.findById(1L)).thenReturn(Optional.of(hold));
        ExplanationWorkspaceService workspace = mock(ExplanationWorkspaceService.class);
        when(workspace.readinessResultForReviewer(eq(1L), any()))
            .thenReturn(new ExplanationWorkspaceService.InvestigationReadinessResult(true, List.of(), List.of(),
                    List.of(), true, false, true));
        ReviewService precheckService = new ReviewService(caseRepository, reviewRepository, eddService, reportService,
                auditOutbox, investigationService, workspace, TestClocks.FIXED);
        when(eddService.openTasks(1L)).thenReturn(List.of());

        precheckService.reviewPrecheck(1L, "reviewer-b", ReviewDecision.EXCLUDE_FALSE_POSITIVE, null, null);
        // 只读：不触发任何写路径
        verify(eddService, never()).transferObligations(any(), any(), any(), any(), any());
    }

}
