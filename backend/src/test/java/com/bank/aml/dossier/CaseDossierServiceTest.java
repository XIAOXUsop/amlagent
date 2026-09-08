package com.bank.aml.dossier;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.entity.InvestigationSnapshotEntity;
import com.bank.aml.datasource.repository.CaseLogRepository;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.datasource.repository.InvestigationSnapshotRepository;
import com.bank.aml.review.ManualReviewRepository;
import com.bank.aml.review.ManualReview;
import com.bank.aml.review.EnhancedDueDiligenceRequest;
import com.bank.aml.review.EnhancedDueDiligenceRequestRepository;
import com.bank.aml.review.EnhancedDueDiligenceStatus;
import com.bank.aml.sanction.SanctionCandidateReview;
import com.bank.aml.sanction.SanctionCandidateReviewRepository;
import com.bank.aml.tools.ToolExecutionTraceRepository;
import com.bank.aml.workflow.CaseExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseDossierServiceTest {

    @Test
    void exportsStableContentHashWithoutEncryptedSnapshotPayload() throws Exception {
        CaseRepository cases = mock(CaseRepository.class);
        CaseLogRepository logs = mock(CaseLogRepository.class);
        CaseExecutionRepository executions = mock(CaseExecutionRepository.class);
        ToolExecutionTraceRepository traces = mock(ToolExecutionTraceRepository.class);
        ManualReviewRepository reviews = mock(ManualReviewRepository.class);
        InvestigationSnapshotRepository snapshots = mock(InvestigationSnapshotRepository.class);
        SanctionCandidateReviewRepository sanctionReviews = mock(SanctionCandidateReviewRepository.class);
        EnhancedDueDiligenceRequestRepository eddRequests = mock(EnhancedDueDiligenceRequestRepository.class);
        com.bank.aml.review.EnhancedDueDiligenceEvidenceRepository eddEvidence =
                mock(com.bank.aml.review.EnhancedDueDiligenceEvidenceRepository.class);
        com.bank.aml.reporting.SuspiciousTransactionReportRepository suspiciousReports =
                mock(com.bank.aml.reporting.SuspiciousTransactionReportRepository.class);
        com.bank.aml.investigation.AmlAlertRepository alerts =
                mock(com.bank.aml.investigation.AmlAlertRepository.class);
        com.bank.aml.investigation.InvestigationHypothesisRepository hypotheses =
                mock(com.bank.aml.investigation.InvestigationHypothesisRepository.class);
        com.bank.aml.investigation.InvestigationEvidenceLinkRepository investigationEvidence =
                mock(com.bank.aml.investigation.InvestigationEvidenceLinkRepository.class);
        com.bank.aml.investigation.AlertInvestigationCoverageRepository alertCoverage =
                mock(com.bank.aml.investigation.AlertInvestigationCoverageRepository.class);
        com.bank.aml.operations.CaseOperationsService operations =
                mock(com.bank.aml.operations.CaseOperationsService.class);

        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setCustomerId("C001");
        caseEntity.setCustomerName("张伟");
        caseEntity.setAlertRule("名单筛查");
        caseEntity.setStatus(CaseStatus.DONE);
        caseEntity.setRiskLevel("高风险");
        caseEntity.setRawRiskLevel("高风险");
        caseEntity.setSnapshotId("snap-1");
        caseEntity.setReportJson("{\"riskLevel\":\"高风险\",\"evidenceChain\":[\"E001\"]}");
        caseEntity.setReviewDisposition("CONFIRM_SUSPICIOUS");
        caseEntity.setReviewReasonCode("SANCTIONS_OR_WATCHLIST_MATCH");

        ManualReview manualReview = new ManualReview();
        manualReview.setCaseId(7L);
        manualReview.setReviewerId("reviewer");
        manualReview.setDecision("CONFIRM_SUSPICIOUS");
        manualReview.setReasonCode("SANCTIONS_OR_WATCHLIST_MATCH");
        manualReview.setComment("身份要素核验后确认名单命中");

        InvestigationSnapshotEntity snapshot = new InvestigationSnapshotEntity();
        snapshot.setSnapshotId("snap-1");
        snapshot.setCaseId(7L);
        snapshot.setExecutionVersion(1);
        snapshot.setAsOfTime(Instant.parse("2026-08-20T00:00:00Z"));
        snapshot.setSourceSystem("TEST");
        snapshot.setSourceVersion("v1");
        snapshot.setLegalIndexVersion("legal-v1");
        snapshot.setSourceDigest("a".repeat(64));
        snapshot.setPayloadCiphertext("SECRET-CIPHERTEXT-MUST-NOT-BE-EXPORTED");

        SanctionCandidateReview sanctionReview = new SanctionCandidateReview();
        sanctionReview.setCustomerId("C001");
        sanctionReview.setCandidateFingerprint("f".repeat(64));
        sanctionReview.setCandidateName("ZHANG WEI");
        sanctionReview.setListType("OFAC");
        sanctionReview.setMatchScore(88);
        sanctionReview.setAlgorithmDecision("REVIEW_REQUIRED");
        sanctionReview.setReviewDecision("CONFIRM");
        sanctionReview.setReviewerId("reviewer");
        sanctionReview.setComment("补充身份要素后确认");
        sanctionReview.setReviewRevision(1);

        when(cases.findById(7L)).thenReturn(Optional.of(caseEntity));
        when(snapshots.findById("snap-1")).thenReturn(Optional.of(snapshot));
        when(logs.findByCaseIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(executions.findByCaseIdOrderByStartedAtAsc(7L)).thenReturn(List.of());
        when(traces.findByCaseIdOrderByExecutionVersionDescSequenceNoAsc(7L)).thenReturn(List.of());
        when(reviews.findByCaseIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(manualReview));
        when(sanctionReviews.findByCustomerIdOrderByCreatedAtAsc("C001")).thenReturn(List.of(sanctionReview));
        when(eddRequests.findByCaseIdOrderByRoundNoAsc(7L)).thenReturn(List.of());
        when(suspiciousReports.findByCaseId(7L)).thenReturn(Optional.empty());
        when(alerts.findByCaseIdOrderByOccurredAtAsc(7L)).thenReturn(List.of());
        when(hypotheses.findByCaseIdOrderByIdAsc(7L)).thenReturn(List.of());
        when(investigationEvidence.findByCaseIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(alertCoverage.findByCaseIdOrderByAlertIdAsc(7L)).thenReturn(List.of());
        LocalDateTime completedAt = LocalDateTime.of(2026, 9, 4, 12, 0);
        when(operations.get(7L)).thenReturn(new com.bank.aml.operations.CaseOperationsView(
                7L, "C001", "张伟", CaseStatus.DONE,
                com.bank.aml.operations.CasePriority.CRITICAL, 100, List.of("名单身份核验场景"),
                "P1_V1_DETERMINISTIC_CASE_PRIORITY", com.bank.aml.operations.OperationPhase.COMPLETED,
                "COMPLETED", null, "已完成", completedAt, null, false, 0,
                "P1_V1_SLA_COMPLETED", completedAt));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CaseDossierService service = new CaseDossierService(cases, logs, executions, traces, reviews, snapshots,
                sanctionReviews, eddRequests, eddEvidence, suspiciousReports, alerts, hypotheses,
                investigationEvidence, alertCoverage, operations, mapper);
        CaseDossier first = service.export(7L);
        CaseDossier second = service.export(7L);

        assertThat(first.contentHash()).hasSize(64).isEqualTo(second.contentHash());
        assertThat(first.schemaVersion()).isEqualTo("1.7");
        assertThat(first.content().reportParseStatus()).isEqualTo("VALID");
        assertThat(first.content().caseSummary().reviewDisposition()).isEqualTo("CONFIRM_SUSPICIOUS");
        assertThat(first.content().reviewHistory()).singleElement()
                .satisfies(review -> assertThat(review.reasonCode()).isEqualTo("SANCTIONS_OR_WATCHLIST_MATCH"));
        assertThat(first.content().snapshot().sourceDigest()).isEqualTo("a".repeat(64));
        assertThat(first.content().sanctionReviewHistory()).singleElement()
                .satisfies(review -> assertThat(review.reviewDecision()).isEqualTo("CONFIRM"));
        // RF-28（G3-3）：解释段按采用提交冻结 payload 回放，不用当前 draftJson 冒充
        var unitRepo = org.mockito.Mockito.mock(com.bank.aml.explanation.AlertExplanationUnitRepository.class);
        var claimRepo = org.mockito.Mockito.mock(com.bank.aml.explanation.ExplanationClaimRepository.class);
        var issueRepo = org.mockito.Mockito.mock(com.bank.aml.explanation.ExplanationIssueRepository.class);
        var basisRepo = org.mockito.Mockito.mock(com.bank.aml.explanation.VerificationBasisRepository.class);
        var submissionRepo = org.mockito.Mockito.mock(com.bank.aml.explanation.ExplanationSubmissionRepository.class);
        var unit = new com.bank.aml.explanation.AlertExplanationUnit();
        setId(unit, 100L);
        unit.setCaseId(7L);
        unit.setAlertId(11L);
        unit.setPolicyCode("GOODS_SETTLED_V1");
        unit.setDraftRevision(5);
        unit.setDraftJson("{\"outcome\":\"SUSPICIOUS\",\"tampered\":true}"); // 草稿被改写
        unit.setCurrentSubmissionId(900L);
        unit.setCreatedBy("analyst");
        unit.setUpdatedAt(LocalDateTime.of(2026, 9, 8, 0, 0));
        var adopted = new com.bank.aml.explanation.ExplanationSubmission();
        setId(adopted, 900L);
        adopted.setUnitId(100L);
        adopted.setCaseId(7L);
        adopted.setSubmissionNo(1);
        adopted.setPayloadJson("{\"outcome\":\"EXPLAINED\",\"frozen\":true}");
        adopted.setOutcome(com.bank.aml.explanation.ExplanationOutcome.EXPLAINED);
        adopted.setState(com.bank.aml.explanation.SubmissionState.CURRENT);
        adopted.setInputDigest("a".repeat(64));
        adopted.setSubmittedBy("analyst");
        adopted.setSubmittedAt(LocalDateTime.of(2026, 9, 5, 0, 0));
        when(unitRepo.findByCaseIdOrderByIdAsc(7L)).thenReturn(List.of(unit));
        when(submissionRepo.findById(900L)).thenReturn(Optional.of(adopted));
        when(claimRepo.findByCaseIdOrderByIdAsc(7L)).thenReturn(List.of());
        when(issueRepo.findByCaseIdOrderByIdAsc(7L)).thenReturn(List.of());
        when(basisRepo.findTopByCaseIdOrderByBasisRevisionDesc(7L)).thenReturn(Optional.empty());

        caseEntity.setInvestigationContractVersion(2); // RF-28 场景：v2 契约案件才输出解释段
        CaseDossierService replayService = new CaseDossierService(cases, logs, executions, traces, reviews,
                snapshots, sanctionReviews, eddRequests, eddEvidence, suspiciousReports, alerts, hypotheses,
                investigationEvidence, alertCoverage, operations, unitRepo, claimRepo, issueRepo, basisRepo,
                submissionRepo, mapper);
        CaseDossier replayed = replayService.export(7L);
        assertThat(replayed.content().explanation()).isNotNull();
        assertThat(replayed.content().explanation().units()).singleElement().satisfies(record -> {
            // 冻结 payload（EXPLAINED）被回放，而非被改写的草稿（SUSPICIOUS）
            assertThat(record.currentPayloadJson()).contains("EXPLAINED").contains("frozen");
            assertThat(record.currentPayloadJson()).doesNotContain("tampered");
            assertThat(record.currentOutcome()).isEqualTo("EXPLAINED");
            assertThat(record.submittedBy()).isEqualTo("analyst");
        });

        assertThat(first.content().operations()).satisfies(operation -> {
            assertThat(operation.priority()).isEqualTo(com.bank.aml.operations.CasePriority.CRITICAL);
            assertThat(operation.priorityPolicy()).isEqualTo("P1_V1_DETERMINISTIC_CASE_PRIORITY");
            assertThat(operation.slaPolicy()).isEqualTo("P1_V1_SLA_COMPLETED");
        });
        assertThat(mapper.writeValueAsString(first)).doesNotContain("SECRET-CIPHERTEXT-MUST-NOT-BE-EXPORTED");
    }

    @Test
    void invalidReportIsMarkedButRawTextIsNotExported() throws Exception {
        CaseRepository cases = mock(CaseRepository.class);
        CaseLogRepository logs = mock(CaseLogRepository.class);
        CaseExecutionRepository executions = mock(CaseExecutionRepository.class);
        ToolExecutionTraceRepository traces = mock(ToolExecutionTraceRepository.class);
        ManualReviewRepository reviews = mock(ManualReviewRepository.class);
        InvestigationSnapshotRepository snapshots = mock(InvestigationSnapshotRepository.class);
        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setCustomerId("C001");
        caseEntity.setStatus(CaseStatus.FAILED);
        caseEntity.setReportJson("{invalid-sensitive-model-text");
        when(cases.findById(9L)).thenReturn(Optional.of(caseEntity));
        when(logs.findByCaseIdOrderByCreatedAtAsc(9L)).thenReturn(List.of());
        when(executions.findByCaseIdOrderByStartedAtAsc(9L)).thenReturn(List.of());
        when(traces.findByCaseIdOrderByExecutionVersionDescSequenceNoAsc(9L)).thenReturn(List.of());
        when(reviews.findByCaseIdOrderByCreatedAtAsc(9L)).thenReturn(List.of());

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CaseDossier dossier = new CaseDossierService(cases, logs, executions, traces, reviews, snapshots, mapper)
                .export(9L);

        assertThat(dossier.content().reportParseStatus()).isEqualTo("INVALID");
        assertThat(dossier.content().report()).isNull();
        assertThat(mapper.writeValueAsString(dossier)).doesNotContain("invalid-sensitive-model-text");
    }

    @Test
    void refusesToExportDossierWhenEddEvidenceHistoryIsCorrupted() {
        CaseRepository cases = mock(CaseRepository.class);
        CaseLogRepository logs = mock(CaseLogRepository.class);
        CaseExecutionRepository executions = mock(CaseExecutionRepository.class);
        ToolExecutionTraceRepository traces = mock(ToolExecutionTraceRepository.class);
        ManualReviewRepository reviews = mock(ManualReviewRepository.class);
        InvestigationSnapshotRepository snapshots = mock(InvestigationSnapshotRepository.class);
        EnhancedDueDiligenceRequestRepository eddRequests = mock(EnhancedDueDiligenceRequestRepository.class);

        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setCustomerId("C001");
        caseEntity.setStatus(CaseStatus.HOLD);
        EnhancedDueDiligenceRequest corrupted = new EnhancedDueDiligenceRequest();
        corrupted.setCaseId(11L);
        corrupted.setRoundNo(1);
        corrupted.setReasonCode("SOURCE_OF_FUNDS_EVIDENCE_REQUIRED");
        corrupted.setRequiredItemsJson("not-json");
        corrupted.setStatus(EnhancedDueDiligenceStatus.OPEN);

        when(cases.findById(11L)).thenReturn(Optional.of(caseEntity));
        when(logs.findByCaseIdOrderByCreatedAtAsc(11L)).thenReturn(List.of());
        when(executions.findByCaseIdOrderByStartedAtAsc(11L)).thenReturn(List.of());
        when(traces.findByCaseIdOrderByExecutionVersionDescSequenceNoAsc(11L)).thenReturn(List.of());
        when(reviews.findByCaseIdOrderByCreatedAtAsc(11L)).thenReturn(List.of());
        when(eddRequests.findByCaseIdOrderByRoundNoAsc(11L)).thenReturn(List.of(corrupted));

        CaseDossierService service = new CaseDossierService(cases, logs, executions, traces, reviews, snapshots,
                null, eddRequests, new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> service.export(11L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enhancedDueDiligence.requiredItems");
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
