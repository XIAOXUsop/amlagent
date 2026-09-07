package com.bank.aml.operations;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.investigation.AlertInvestigationCoverage;
import com.bank.aml.investigation.AlertInvestigationCoverageRepository;
import com.bank.aml.investigation.AlertStatus;
import com.bank.aml.investigation.AmlAlert;
import com.bank.aml.investigation.AmlAlertRepository;
import com.bank.aml.investigation.HypothesisStatus;
import com.bank.aml.investigation.InvestigationEvidenceLinkRepository;
import com.bank.aml.investigation.InvestigationHypothesis;
import com.bank.aml.investigation.InvestigationHypothesisRepository;
import com.bank.aml.investigation.InvestigationReadinessEvaluator;
import com.bank.aml.review.EnhancedDueDiligenceRequest;
import com.bank.aml.review.EnhancedDueDiligenceRequestRepository;
import com.bank.aml.review.EnhancedDueDiligenceStatus;
import com.bank.aml.reporting.SuspiciousTransactionReport;
import com.bank.aml.reporting.SuspiciousTransactionReportRepository;
import com.bank.aml.reporting.SuspiciousTransactionReportStatus;
import com.bank.aml.workflow.CaseExecution;
import com.bank.aml.workflow.CaseExecutionRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseOperationsServiceTest {
    private final ZoneId zone = ZoneId.of("Asia/Shanghai");
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-04T04:00:00Z"), zone);
    private final LocalDateTime now = LocalDateTime.now(clock);
    private final CaseRepository cases = mock(CaseRepository.class);
    private final AmlAlertRepository alerts = mock(AmlAlertRepository.class);
    private final EnhancedDueDiligenceRequestRepository edd = mock(EnhancedDueDiligenceRequestRepository.class);
    private final CaseExecutionRepository executions = mock(CaseExecutionRepository.class);
    private final SuspiciousTransactionReportRepository reports = mock(SuspiciousTransactionReportRepository.class);
    private final CaseOperationsService service = new CaseOperationsService(cases, alerts, edd, executions,
            reports, clock);
    private final InvestigationHypothesisRepository hypotheses =
            mock(InvestigationHypothesisRepository.class);
    private final AlertInvestigationCoverageRepository coverage =
            mock(AlertInvestigationCoverageRepository.class);
    private final InvestigationEvidenceLinkRepository evidence =
            mock(InvestigationEvidenceLinkRepository.class);
    private final CaseOperationsService investigationAwareService =
            new CaseOperationsService(cases, alerts, edd, executions, reports, hypotheses, coverage,
                    evidence, new InvestigationReadinessEvaluator(), null, clock);

    @Test
    void sanctionAlertIsCriticalAndSlaStartsWhenAlertEnteredSystem() {
        CaseEntity caseEntity = caseEntity(1L, CaseStatus.PENDING);
        AmlAlert alert = mock(AmlAlert.class);
        when(alert.getStatus()).thenReturn(AlertStatus.LINKED);
        when(alert.getScenarioCode()).thenReturn("SANCTIONS_WATCHLIST");
        when(alert.getOccurredAt()).thenReturn(now.minusDays(180));
        when(alert.getCreatedAt()).thenReturn(now.minusHours(5));
        when(cases.findById(1L)).thenReturn(Optional.of(caseEntity));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(1L)).thenReturn(List.of(alert));

        CaseOperationsView result = service.get(1L);

        assertThat(result.priority()).isEqualTo(CasePriority.CRITICAL);
        assertThat(result.priorityScore()).isEqualTo(100);
        assertThat(result.clockStartedAt()).isEqualTo(now.minusHours(5));
        assertThat(result.dueAt()).isEqualTo(now.minusHours(1));
        assertThat(result.overdue()).isTrue();
        assertThat(result.priorityReasons()).contains("名单身份核验场景");
    }

    @Test
    void openEddUsesExplicitDeadlineAndNamedAssigneeAtExactBoundary() {
        CaseEntity caseEntity = caseEntity(2L, CaseStatus.HOLD);
        EnhancedDueDiligenceRequest request = new EnhancedDueDiligenceRequest();
        request.setStatus(EnhancedDueDiligenceStatus.OPEN);
        request.setRequestedAt(now.minusDays(2));
        request.setDueAt(now);
        request.setAssignedTo("analyst");
        request.setAssignedUnit("反洗钱分析组");
        when(cases.findById(2L)).thenReturn(Optional.of(caseEntity));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(2L)).thenReturn(List.of());
        when(edd.findTopByCaseIdOrderByRoundNoDesc(2L)).thenReturn(Optional.of(request));

        CaseOperationsView result = service.get(2L);

        assertThat(result.phase()).isEqualTo(OperationPhase.ENHANCED_DUE_DILIGENCE);
        assertThat(result.assignedTo()).isEqualTo("analyst");
        assertThat(result.dueAt()).isEqualTo(now);
        assertThat(result.overdue()).isTrue();
        assertThat(result.slaPolicy()).isEqualTo("P1_V1_EDD_EXPLICIT_DUE_AT");
        assertThat(result.priorityPolicy()).isEqualTo("P1_V1_DETERMINISTIC_CASE_PRIORITY");
        assertThat(result.calculatedAt()).isEqualTo(now);
    }

    @Test
    void returnedReportRestartsReportingClockAndHasCriticalPriorityFloor() {
        CaseEntity caseEntity = caseEntity(5L, CaseStatus.REPORT_PENDING);
        SuspiciousTransactionReport report = new SuspiciousTransactionReport();
        report.setStatus(SuspiciousTransactionReportStatus.RETURNED_FOR_CORRECTION);
        report.setReturnedAt(now.minusHours(1));
        when(cases.findById(5L)).thenReturn(Optional.of(caseEntity));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(5L)).thenReturn(List.of());
        when(reports.findByCaseId(5L)).thenReturn(Optional.of(report));

        CaseOperationsView result = service.get(5L);

        assertThat(result.priority()).isEqualTo(CasePriority.CRITICAL);
        assertThat(result.priorityScore()).isEqualTo(85);
        assertThat(result.clockStartedAt()).isEqualTo(now.minusHours(1));
        assertThat(result.dueAt()).isEqualTo(now.plusHours(3));
    }

    @Test
    void duplicateAlertsFromSameScenarioDoNotInflateScenarioWeight() {
        CaseEntity caseEntity = caseEntity(6L, CaseStatus.PENDING);
        AmlAlert first = linkedAlert("PROFILE_MISMATCH", now.minusHours(2));
        AmlAlert second = linkedAlert("PROFILE_MISMATCH", now.minusHours(1));
        when(cases.findById(6L)).thenReturn(Optional.of(caseEntity));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(6L)).thenReturn(List.of(first, second));

        CaseOperationsView result = service.get(6L);

        assertThat(result.priorityScore()).isEqualTo(45);
        assertThat(result.priority()).isEqualTo(CasePriority.MEDIUM);
        assertThat(result.priorityReasons()).containsExactly(
                "交易与客户画像不匹配", "同一客户聚合 2 条有效预警");
    }

    @Test
    void completedCaseHasNoArtificialDeadlineOrOverdueState() {
        CaseEntity caseEntity = caseEntity(7L, CaseStatus.DONE);
        when(cases.findById(7L)).thenReturn(Optional.of(caseEntity));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(7L)).thenReturn(List.of());

        CaseOperationsView result = service.get(7L);

        assertThat(result.phase()).isEqualTo(OperationPhase.COMPLETED);
        assertThat(result.dueAt()).isNull();
        assertThat(result.overdue()).isFalse();
        assertThat(result.slaPolicy()).isEqualTo("P1_V1_SLA_COMPLETED");
    }

    @Test
    void queuesAreSeparatedByRoleAndEddAssignee() {
        CaseEntity investigation = caseEntity(1L, CaseStatus.PENDING);
        CaseEntity eddCase = caseEntity(2L, CaseStatus.HOLD);
        CaseEntity review = caseEntity(3L, CaseStatus.HOLD);
        CaseEntity reporting = caseEntity(4L, CaseStatus.REPORT_PENDING);
        when(cases.findByStatusInOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(investigation, eddCase, review, reporting));
        for (long id = 1; id <= 4; id++) {
            when(alerts.findByCaseIdOrderByOccurredAtAsc(id)).thenReturn(List.of());
        }
        EnhancedDueDiligenceRequest request = new EnhancedDueDiligenceRequest();
        request.setStatus(EnhancedDueDiligenceStatus.OPEN);
        request.setRequestedAt(now.minusHours(1));
        request.setDueAt(now.plusDays(2));
        request.setAssignedTo("analyst-a");
        request.setAssignedUnit("反洗钱分析组");
        when(edd.findTopByCaseIdOrderByRoundNoDesc(2L)).thenReturn(Optional.of(request));
        when(edd.findTopByCaseIdOrderByRoundNoDesc(3L)).thenReturn(Optional.empty());
        when(executions.findByCaseIdOrderByStartedAtAsc(3L)).thenReturn(List.of());
        when(reports.findByCaseId(4L)).thenReturn(Optional.empty());

        assertThat(service.queue("analyst-a", "ANALYST", false, null, null))
                .extracting(CaseOperationsView::caseId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(service.queue("analyst-b", "ANALYST", false, null, null))
                .extracting(CaseOperationsView::caseId).containsExactly(1L);
        assertThat(service.queue("reviewer", "REVIEWER", false, null, null))
                .extracting(CaseOperationsView::caseId).containsExactlyInAnyOrder(3L, 4L);
        assertThat(service.queue("admin", "ADMIN", false, null, null))
                .extracting(CaseOperationsView::caseId).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    private CaseEntity caseEntity(Long id, CaseStatus status) {
        CaseEntity entity = mock(CaseEntity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getCustomerId()).thenReturn("C00" + id);
        when(entity.getCustomerName()).thenReturn("客户" + id);
        when(entity.getStatus()).thenReturn(status);
        when(entity.getCreatedAt()).thenReturn(now.minusHours(1));
        when(entity.getUpdatedAt()).thenReturn(now.minusHours(1));
        when(entity.getReviewedAt()).thenReturn(now.minusHours(1));
        return entity;
    }

    private CaseEntity v1Case(Long id, CaseStatus status) {
        CaseEntity entity = caseEntity(id, status);
        when(entity.getInvestigationContractVersion()).thenReturn(1);
        return entity;
    }

    private AmlAlert linkedAlert(String scenario, LocalDateTime createdAt) {
        AmlAlert alert = mock(AmlAlert.class);
        when(alert.getStatus()).thenReturn(AlertStatus.LINKED);
        when(alert.getScenarioCode()).thenReturn(scenario);
        when(alert.getCreatedAt()).thenReturn(createdAt);
        when(alert.getOccurredAt()).thenReturn(createdAt);
        when(alert.getId()).thenReturn(11L);
        when(alert.getExternalAlertId()).thenReturn("ALERT-A");
        return alert;
    }

    // ---- T13：HOLD 按调查就绪分流；SLA 起点只用可持久化事实，不随查询漂移 ----

    @Test
    void holdWithIncompleteInvestigationBelongsToAnalystInvestigationQueue() {
        CaseEntity caseEntity = v1Case(8L, CaseStatus.HOLD);
        AmlAlert alert = linkedAlert("STRUCTURING", now.minusHours(4));
        when(cases.findById(8L)).thenReturn(Optional.of(caseEntity));
        when(cases.findByStatusInOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(caseEntity));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(8L)).thenReturn(List.of(alert));
        when(edd.findTopByCaseIdOrderByRoundNoDesc(8L)).thenReturn(Optional.empty());
        InvestigationHypothesis open = mock(InvestigationHypothesis.class);
        when(open.getId()).thenReturn(21L);
        when(open.getStatus()).thenReturn(HypothesisStatus.OPEN);
        when(hypotheses.findByCaseIdOrderByIdAsc(8L)).thenReturn(List.of(open));
        when(hypotheses.findByCaseIdInOrderByIdAsc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(open));
        AlertInvestigationCoverage pending = mock(AlertInvestigationCoverage.class);
        when(pending.getAlertId()).thenReturn(11L);
        when(pending.getConclusion()).thenReturn(com.bank.aml.investigation.AlertCoverageConclusion.PENDING);
        when(coverage.findByCaseIdOrderByAlertIdAsc(8L)).thenReturn(List.of(pending));
        when(coverage.findByCaseIdInOrderByAlertIdAsc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(pending));
        when(evidence.findByCaseIdOrderByCreatedAtAsc(8L)).thenReturn(List.of());
        when(evidence.findByCaseIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        CaseOperationsView result = investigationAwareService.get(8L);

        assertThat(result.phase()).isEqualTo(OperationPhase.INVESTIGATION);
        assertThat(result.responsibleRole()).isEqualTo("ANALYST");
        assertThat(result.priorityReasons()).anyMatch(reason -> reason.contains("覆盖"));
        // 分析员可见，复核员不可见（未就绪不进复核队列）
        assertThat(investigationAwareService.queue("analyst-a", "ANALYST", false, null, null))
                .extracting(CaseOperationsView::caseId).contains(8L);
        assertThat(investigationAwareService.queue("reviewer", "REVIEWER", false, null, null))
                .extracting(CaseOperationsView::caseId).doesNotContain(8L);
    }

    @Test
    void holdWithReadyInvestigationGoesToReviewerAndReviewStartUsesPersistedEvents() {
        CaseEntity caseEntity = v1Case(9L, CaseStatus.HOLD);
        AmlAlert alert = linkedAlert("STRUCTURING", now.minusHours(6));
        when(cases.findById(9L)).thenReturn(Optional.of(caseEntity));
        when(cases.findByStatusInOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(caseEntity));
        when(alerts.findByCaseIdOrderByOccurredAtAsc(9L)).thenReturn(List.of(alert));
        when(edd.findTopByCaseIdOrderByRoundNoDesc(9L)).thenReturn(Optional.empty());
        // 自动分析完成于 3 小时前；覆盖结论完成于 1 小时前 → 复核阶段起点 = 1 小时前
        CaseExecution execution = mock(CaseExecution.class);
        when(execution.getCompletedAt()).thenReturn(now.minusHours(3));
        when(executions.findByCaseIdOrderByStartedAtAsc(9L)).thenReturn(List.of(execution));
        InvestigationHypothesis confirmed = decidedHypothesis(21L, HypothesisStatus.CONFIRMED, now.minusHours(2));
        AlertInvestigationCoverage suspicious = suspiciousCoverage(now.minusHours(1));
        com.bank.aml.investigation.InvestigationEvidenceLink support =
                evidence(21L, com.bank.aml.investigation.InvestigationEvidenceType.TRANSACTION,
                        com.bank.aml.investigation.EvidenceStance.SUPPORTS);
        when(hypotheses.findByCaseIdOrderByIdAsc(9L)).thenReturn(List.of(confirmed));
        when(hypotheses.findByCaseIdInOrderByIdAsc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(confirmed));
        when(coverage.findByCaseIdOrderByAlertIdAsc(9L)).thenReturn(List.of(suspicious));
        when(coverage.findByCaseIdInOrderByAlertIdAsc(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(suspicious));
        when(evidence.findByCaseIdOrderByCreatedAtAsc(9L)).thenReturn(List.of(support));
        when(evidence.findByCaseIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(support));

        CaseOperationsView first = investigationAwareService.get(9L);
        CaseOperationsView second = investigationAwareService.get(9L);

        assertThat(first.phase()).isEqualTo(OperationPhase.REVIEW);
        assertThat(first.responsibleRole()).isEqualTo("REVIEWER");
        // SLA 起点只用已持久化事实（分析完成/覆盖结论完成的最晚时间），两次查询不漂移
        assertThat(first.clockStartedAt()).isEqualTo(now.minusHours(1));
        assertThat(second.clockStartedAt()).isEqualTo(first.clockStartedAt());
        assertThat(second.dueAt()).isEqualTo(first.dueAt());
        // 就绪后复核员可见
        var reviewerQueue = investigationAwareService.queue("reviewer", "REVIEWER", false, null, null);
        assertThat(reviewerQueue).extracting(CaseOperationsView::caseId).contains(9L);
    }

    private InvestigationHypothesis decidedHypothesis(Long id, HypothesisStatus status, LocalDateTime updatedAt) {
        InvestigationHypothesis hypothesis = mock(InvestigationHypothesis.class);
        when(hypothesis.getId()).thenReturn(id);
        when(hypothesis.getCaseId()).thenReturn(9L);
        when(hypothesis.getStatus()).thenReturn(status);
        when(hypothesis.getTitle()).thenReturn("假设" + id);
        when(hypothesis.getRequiredEvidenceTypes()).thenReturn("TRANSACTION");
        when(hypothesis.getRevision()).thenReturn(1);
        when(hypothesis.getUpdatedAt()).thenReturn(updatedAt);
        return hypothesis;
    }

    private AlertInvestigationCoverage suspiciousCoverage(LocalDateTime updatedAt) {
        AlertInvestigationCoverage item = mock(AlertInvestigationCoverage.class);
        when(item.getAlertId()).thenReturn(11L);
        when(item.getCaseId()).thenReturn(9L);
        when(item.getHypothesisId()).thenReturn(21L);
        when(item.getHypothesisRevision()).thenReturn(1L);
        when(item.getConclusion()).thenReturn(com.bank.aml.investigation.AlertCoverageConclusion.SUSPICIOUS);
        when(item.getUpdatedAt()).thenReturn(updatedAt);
        return item;
    }

    private com.bank.aml.investigation.InvestigationEvidenceLink evidence(Long hypothesisId,
            com.bank.aml.investigation.InvestigationEvidenceType type,
            com.bank.aml.investigation.EvidenceStance stance) {
        com.bank.aml.investigation.InvestigationEvidenceLink link =
                mock(com.bank.aml.investigation.InvestigationEvidenceLink.class);
        when(link.getCaseId()).thenReturn(9L);
        when(link.getHypothesisId()).thenReturn(hypothesisId);
        when(link.getEvidenceType()).thenReturn(type);
        when(link.getStance()).thenReturn(stance);
        return link;
    }
}
