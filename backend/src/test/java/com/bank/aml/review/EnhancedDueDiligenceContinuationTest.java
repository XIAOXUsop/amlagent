package com.bank.aml.review;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.security.UserAccount;
import com.bank.aml.security.UserAccountRepository;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.explanation.EddTaskPurpose;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2 计划 §8：EDD 目的分离与义务接续回归。
 * 覆盖 V2-06/07（接续创建 + SUPERSEDED 非 RESOLVED）、V2-17（父案 DONE 后持续任务可提交）、
 * V2-24（CONTINUING_REVIEW 不阻断最终处置）。
 */
class EnhancedDueDiligenceContinuationTest {

    private static final Long CASE_ID = 7L;

    private final EnhancedDueDiligenceRequestRepository repository =
            mock(EnhancedDueDiligenceRequestRepository.class);
    private final EnhancedDueDiligenceEvidenceRepository evidenceRepository =
            mock(EnhancedDueDiligenceEvidenceRepository.class);
    private final CaseRepository caseRepository = mock(CaseRepository.class);
    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);
    private final AuditOutboxService auditOutbox = mock(AuditOutboxService.class);

    private final EnhancedDueDiligenceService service = new EnhancedDueDiligenceService(repository,
            evidenceRepository, caseRepository, userAccountRepository, auditOutbox, new ObjectMapper());

    private CaseEntity holdCase;

    @BeforeEach
    void setUp() {
        holdCase = new CaseEntity();
        setId(holdCase, CASE_ID);
        holdCase.setStatus(CaseStatus.HOLD);
        when(caseRepository.findById(CASE_ID)).thenReturn(Optional.of(holdCase));
        when(caseRepository.findByIdForUpdate(CASE_ID)).thenReturn(Optional.of(holdCase));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findTopByCaseIdOrderByRoundNoDesc(CASE_ID)).thenReturn(Optional.empty());
        when(repository.findByCaseIdAndStatusOrderByIdAsc(eq(CASE_ID), any())).thenReturn(List.of());
        UserAccount account = new UserAccount();
        account.setUsername("analyst");
        account.setRole("ANALYST");
        account.setEnabled(true);
        when(userAccountRepository.findByUsername("analyst")).thenReturn(Optional.of(account));
    }

    /** V2-24：OPEN 的 CONTINUING_REVIEW 不阻断最终处置；OPEN 的 DECISION_SUPPORT 阻断。 */
    @Test
    void openContinuingReviewDoesNotBlockFinalDecisionButDecisionSupportDoes() {
        EnhancedDueDiligenceRequest continuing = task(21L, 2, EddTaskPurpose.CONTINUING_REVIEW,
                EnhancedDueDiligenceStatus.OPEN);
        when(repository.findByCaseIdAndStatusOrderByIdAsc(CASE_ID, EnhancedDueDiligenceStatus.OPEN))
                .thenReturn(List.of(continuing));

        service.validateReviewDecision(CASE_ID, ReviewDecision.EXCLUDE_FALSE_POSITIVE,
                List.of(), null, null, null);

        EnhancedDueDiligenceRequest decisionSupport = task(22L, 3, EddTaskPurpose.DECISION_SUPPORT,
                EnhancedDueDiligenceStatus.OPEN);
        when(repository.findByCaseIdAndStatusOrderByIdAsc(CASE_ID, EnhancedDueDiligenceStatus.OPEN))
                .thenReturn(List.of(decisionSupport));

        assertThatThrownBy(() -> service.validateReviewDecision(CASE_ID,
                ReviewDecision.EXCLUDE_FALSE_POSITIVE, List.of(), null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("决策支持");
    }

    /** V2-07：义务接续同一事务内——创建 CONTINUING_REVIEW，原任务 CANCELLED+SUPERSEDED（非 RESOLVED）。 */
    @Test
    void transferObligationsCreatesContinuingTasksAndCancelsSupersededAsNotResolved() {
        EnhancedDueDiligenceRequest openSupport = task(22L, 1, EddTaskPurpose.DECISION_SUPPORT,
                EnhancedDueDiligenceStatus.OPEN);
        when(repository.findByCaseIdAndStatusOrderByIdAsc(CASE_ID, EnhancedDueDiligenceStatus.OPEN))
                .thenReturn(List.of(openSupport));
        when(repository.findTopByCaseIdOrderByRoundNoDesc(CASE_ID))
                .thenReturn(Optional.of(openSupport));
        when(repository.findByIdAndCaseId(22L, CASE_ID)).thenReturn(Optional.of(openSupport));

        List<EnhancedDueDiligenceService.ContinuationTaskPlan> plans = List.of(
                new EnhancedDueDiligenceService.ContinuationTaskPlan(22L, "analyst", "调查一组",
                        LocalDateTime.now().plusDays(5), List.of("TRANSACTION_PURPOSE"),
                        "完成收款主体与合同买方一致性的独立核验并记录方法与观察",
                        "{\"issueIds\":[9],\"standard\":\"核验完成并复核\"}"));

        service.transferObligations(CASE_ID, "reviewer", LocalDateTime.now(), plans, 88L);

        // 原任务：CANCELLED + SUPERSEDED_BY_CONTINUING_REVIEW，不得标为 RESOLVED
        assertThat(openSupport.getStatus()).isEqualTo(EnhancedDueDiligenceStatus.CANCELLED);
        assertThat(openSupport.getResolutionReason()).isEqualTo("SUPERSEDED_BY_CONTINUING_REVIEW");
        // 新任务：CONTINUING_REVIEW，绑定原任务/复核与完成标准
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.atLeastOnce())
                .save(org.mockito.ArgumentMatchers.argThat(saved -> saved instanceof EnhancedDueDiligenceRequest t
                        && t.getPurpose() == EddTaskPurpose.CONTINUING_REVIEW));
    }

    /** 接续计划缺完成标准 → 拒绝（§8.2：不能假装已完成义务）。 */
    @Test
    void continuationPlanWithoutCompletionStandardIsRejected() {
        List<EnhancedDueDiligenceService.ContinuationTaskPlan> plans = List.of(
                new EnhancedDueDiligenceService.ContinuationTaskPlan(22L, "analyst", "调查一组",
                        LocalDateTime.now().plusDays(5), List.of(), "以后再查", null));

        assertThatThrownBy(() -> service.transferObligations(CASE_ID, "reviewer", LocalDateTime.now(),
                plans, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("完成标准");
    }

    /** TP-21（A5-07）：无关接续计划（originRequestId 不存在）→ 拒绝；不得遍历取消全部 OPEN 任务。 */
    @Test
    void unrelatedPlanIsRejectedAndOtherTasksRemainOpen() {
        var first = task(22L, 1, EddTaskPurpose.DECISION_SUPPORT, EnhancedDueDiligenceStatus.OPEN);
        var second = task(23L, 2, EddTaskPurpose.DECISION_SUPPORT, EnhancedDueDiligenceStatus.OPEN);
        when(repository.findByCaseIdAndStatusOrderByIdAsc(CASE_ID, EnhancedDueDiligenceStatus.OPEN))
                .thenReturn(List.of(first, second));
        when(repository.findByIdAndCaseId(999L, CASE_ID)).thenReturn(Optional.empty());
        var plan = new EnhancedDueDiligenceService.ContinuationTaskPlan(999L, "analyst", "team-a",
                LocalDateTime.now().plusDays(3), List.of("TRANSACTION_PURPOSE"),
                "Verify a completely unrelated transaction purpose.", "{\"issueIds\":[]}");
        assertThatThrownBy(() -> service.transferObligations(CASE_ID, "reviewer",
                LocalDateTime.now(), List.of(plan), 88L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或不属于本案件");
        // 两个原任务都不被取消（逐项对应，不遍历取消）
        assertThat(first.getStatus()).isEqualTo(EnhancedDueDiligenceStatus.OPEN);
        assertThat(second.getStatus()).isEqualTo(EnhancedDueDiligenceStatus.OPEN);
    }

    /** TP-21：计划只覆盖一个原任务 → 只有被引用的任务被取消；未覆盖的保持 OPEN 阻断。 */
    @Test
    void partialCoverageOnlyCancelsReferencedOrigin() {
        var first = task(22L, 1, EddTaskPurpose.DECISION_SUPPORT, EnhancedDueDiligenceStatus.OPEN);
        var second = task(23L, 2, EddTaskPurpose.DECISION_SUPPORT, EnhancedDueDiligenceStatus.OPEN);
        when(repository.findByCaseIdAndStatusOrderByIdAsc(CASE_ID, EnhancedDueDiligenceStatus.OPEN))
                .thenReturn(List.of(first, second));
        when(repository.findTopByCaseIdOrderByRoundNoDesc(CASE_ID)).thenReturn(Optional.of(first));
        when(repository.findByIdAndCaseId(22L, CASE_ID)).thenReturn(Optional.of(first));
        when(repository.findByIdAndCaseId(23L, CASE_ID)).thenReturn(Optional.of(second));
        var plan = new EnhancedDueDiligenceService.ContinuationTaskPlan(22L, "analyst", "team-a",
                LocalDateTime.now().plusDays(3), List.of("TRANSACTION_PURPOSE"),
                "核验第一项义务：收款主体一致性，观察与方法已记录", "{\"issueIds\":[9]}");
        service.transferObligations(CASE_ID, "reviewer", LocalDateTime.now(), List.of(plan), 88L);
        // 只有被引用的 first 被取消；second 保持 OPEN（义务守恒）
        assertThat(first.getStatus()).isEqualTo(EnhancedDueDiligenceStatus.CANCELLED);
        assertThat(second.getStatus()).isEqualTo(EnhancedDueDiligenceStatus.OPEN);
    }

    /** 同一原任务被多个计划接替 → 拒绝。 */
    @Test
    void duplicateOriginReferenceIsRejected() {
        var origin = task(22L, 1, EddTaskPurpose.DECISION_SUPPORT, EnhancedDueDiligenceStatus.OPEN);
        when(repository.findByCaseIdAndStatusOrderByIdAsc(CASE_ID, EnhancedDueDiligenceStatus.OPEN))
                .thenReturn(List.of(origin));
        when(repository.findByIdAndCaseId(22L, CASE_ID)).thenReturn(Optional.of(origin));
        var plan1 = new EnhancedDueDiligenceService.ContinuationTaskPlan(22L, "analyst", "team-a",
                LocalDateTime.now().plusDays(3), List.of("TRANSACTION_PURPOSE"),
                "核验第一项：收款主体一致性的完整记录", null);
        var plan2 = new EnhancedDueDiligenceService.ContinuationTaskPlan(22L, "analyst", "team-b",
                LocalDateTime.now().plusDays(4), List.of("SOURCE_OF_FUNDS"),
                "核验第二项：资金来源的完整记录", null);
        assertThatThrownBy(() -> service.transferObligations(CASE_ID, "reviewer",
                LocalDateTime.now(), List.of(plan1, plan2), 88L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一原任务被多个接续计划引用");
    }

    /** V2-17：父案 DONE 后，OPEN 的 CONTINUING_REVIEW 仍可提交材料；DECISION_SUPPORT 不行。 */
    @Test
    void continuingTaskCanBeSubmittedAfterCaseDone() {
        holdCase.setStatus(CaseStatus.DONE);
        EnhancedDueDiligenceRequest continuing = task(21L, 2, EddTaskPurpose.CONTINUING_REVIEW,
                EnhancedDueDiligenceStatus.OPEN);
        continuing.setAssignedTo("analyst");
        continuing.setRequiredItemsJson("[\"TRANSACTION_PURPOSE\"]");
        when(repository.findByIdAndCaseId(21L, CASE_ID)).thenReturn(Optional.of(continuing));
        when(repository.submitResponse(eq(21L), eq(CASE_ID), any(), any(), eq(0),
                any(), any(), any(), any())).thenReturn(1);
        when(evidenceRepository.saveAllAndFlush(any())).thenReturn(List.of());
        when(repository.findById(21L)).thenReturn(Optional.of(continuing));

        EnhancedDueDiligenceView view = service.submitResponse(CASE_ID, 21L, 0,
                "已完成收款主体一致性核验：核心系统流水与合同买方一致", List.of(
                        new EnhancedDueDiligenceEvidenceSubmission("TRANSACTION_PURPOSE", "CORE_BANKING",
                                "TXN-DOC-009", "a".repeat(64))),
                "analyst", false);
        assertThat(view).isNotNull();

        // DECISION_SUPPORT 在 DONE 状态不可提交
        EnhancedDueDiligenceRequest support = task(22L, 3, EddTaskPurpose.DECISION_SUPPORT,
                EnhancedDueDiligenceStatus.OPEN);
        support.setAssignedTo("analyst");
        support.setRequiredItemsJson("[\"SOURCE_OF_FUNDS\"]");
        when(repository.findByIdAndCaseId(22L, CASE_ID)).thenReturn(Optional.of(support));
        assertThatThrownBy(() -> service.submitResponse(CASE_ID, 22L, 0,
                "补充说明材料", List.of(new EnhancedDueDiligenceEvidenceSubmission("SOURCE_OF_FUNDS",
                        "CORE_BANKING", "TXN-DOC-010", "b".repeat(64))),
                "analyst", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("人工复核状态");
    }

    /** §8.3：RESOLVED 只能由明确完成产生；不再因“最近一轮”被其他业务决定自动 RESOLVED。 */
    @Test
    void applyReviewDecisionNoLongerAutoResolvesSubmittedTasks() {
        EnhancedDueDiligenceRequest submitted = task(23L, 1, EddTaskPurpose.DECISION_SUPPORT,
                EnhancedDueDiligenceStatus.SUBMITTED);
        when(repository.findTopByCaseIdOrderByRoundNoDesc(CASE_ID)).thenReturn(Optional.of(submitted));

        service.applyReviewDecision(CASE_ID, ReviewDecision.EXCLUDE_FALSE_POSITIVE, ReviewReasonCode.VERIFIED_LEGITIMATE_PURPOSE,
                List.of(), null, null, null, "reviewer", LocalDateTime.now());

        assertThat(submitted.getStatus()).isEqualTo(EnhancedDueDiligenceStatus.SUBMITTED);
    }

    /** 显式完成任务：REVIEWER 以 requestId/expectedRevision 确认，RESOLVED 带核验结论。 */
    @Test
    void explicitCompletionResolvesTaskWithReason() {
        EnhancedDueDiligenceRequest submittedTask = task(21L, 2, EddTaskPurpose.CONTINUING_REVIEW,
                EnhancedDueDiligenceStatus.SUBMITTED);
        submittedTask.setAssignedTo("analyst");
        when(repository.findByIdAndCaseId(21L, CASE_ID)).thenReturn(Optional.of(submittedTask));
        when(repository.findById(21L)).thenAnswer(inv -> {
            submittedTask.setStatus(EnhancedDueDiligenceStatus.RESOLVED);
            submittedTask.setResolvedBy("reviewer-b");
            return Optional.of(submittedTask);
        });
        // 条件更新：版本与状态绑定，成功返回 1
        when(repository.completeTask(eq(21L), eq(CASE_ID),
                eq(EnhancedDueDiligenceStatus.SUBMITTED),
                eq(EnhancedDueDiligenceStatus.RESOLVED),
                eq(0), any(), eq("reviewer-b"), any())).thenReturn(1);

        EnhancedDueDiligenceView view = service.completeTask(CASE_ID, 21L, 0,
                "复核确认：收款主体一致性核验完成，观察与限制已记录", "reviewer-b");

        assertThat(submittedTask.getResolvedBy()).isEqualTo("reviewer-b");
        assertThat(view).isNotNull();
    }

    /** A5-08：材料提交人不能自行完成核验；expectedRevision 参与条件更新。 */
    @Test
    void submitterCannotCompleteOwnTaskAndStaleRevisionIsRejected() {
        EnhancedDueDiligenceRequest submittedTask = task(21L, 2, EddTaskPurpose.CONTINUING_REVIEW,
                EnhancedDueDiligenceStatus.SUBMITTED);
        submittedTask.setAssignedTo("analyst");
        submittedTask.setRespondedBy("admin-a");
        submittedTask.setRevision(5);
        when(repository.findByIdAndCaseId(21L, CASE_ID)).thenReturn(Optional.of(submittedTask));

        // 材料提交人自行完成 → 拒绝（ADMIN 兼具双权限同样禁止自审）
        assertThatThrownBy(() -> service.completeTask(CASE_ID, 21L, 5, "I reviewed my own submission.", "admin-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能自行完成核验");
        // 旧版本（expectedRevision=0 vs 实际 5）→ 409 冲突
        assertThatThrownBy(() -> service.completeTask(CASE_ID, 21L, 0, "复核确认：核验完成，依据已记录", "reviewer-b"))
                .isInstanceOf(com.bank.aml.common.exception.InvestigationRevisionConflictException.class)
                .hasMessageContaining("版本已变化");
        // 竞争条件更新失败（另一位已抢先完成）→ 409
        when(repository.completeTask(eq(21L), eq(CASE_ID),
                eq(EnhancedDueDiligenceStatus.SUBMITTED),
                eq(EnhancedDueDiligenceStatus.RESOLVED),
                eq(5), any(), eq("reviewer-b"), any())).thenReturn(0);
        assertThatThrownBy(() -> service.completeTask(CASE_ID, 21L, 5, "复核确认：核验完成，依据已记录", "reviewer-b"))
                .isInstanceOf(com.bank.aml.common.exception.InvestigationRevisionConflictException.class)
                .hasMessageContaining("已被他人完成");
        assertThat(submittedTask.getStatus()).isEqualTo(EnhancedDueDiligenceStatus.SUBMITTED);
    }

    private EnhancedDueDiligenceRequest task(Long id, int round, EddTaskPurpose purpose,
                                             EnhancedDueDiligenceStatus status) {
        EnhancedDueDiligenceRequest request = new EnhancedDueDiligenceRequest();
        setId(request, id);
        request.setCaseId(CASE_ID);
        request.setRoundNo(round);
        request.setReasonCode("SOURCE_OF_FUNDS_UNCLEAR");
        request.setRequiredItemsJson("[\"SOURCE_OF_FUNDS\"]");
        request.setRequestedBy("reviewer");
        request.setRequestedAt(LocalDateTime.now());
        request.setDueAt(LocalDateTime.now().plusDays(3));
        request.setStatus(status);
        request.setPurpose(purpose);
        request.setRevision(0);
        return request;
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
