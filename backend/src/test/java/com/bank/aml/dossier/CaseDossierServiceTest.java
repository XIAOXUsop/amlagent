package com.bank.aml.dossier;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.entity.InvestigationSnapshotEntity;
import com.bank.aml.datasource.repository.CaseLogRepository;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.datasource.repository.InvestigationSnapshotRepository;
import com.bank.aml.review.ManualReviewRepository;
import com.bank.aml.sanction.SanctionCandidateReview;
import com.bank.aml.sanction.SanctionCandidateReviewRepository;
import com.bank.aml.tools.ToolExecutionTraceRepository;
import com.bank.aml.workflow.CaseExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setCustomerId("C001");
        caseEntity.setCustomerName("张伟");
        caseEntity.setAlertRule("名单筛查");
        caseEntity.setStatus(CaseStatus.DONE);
        caseEntity.setRiskLevel("高风险");
        caseEntity.setRawRiskLevel("高风险");
        caseEntity.setSnapshotId("snap-1");
        caseEntity.setReportJson("{\"riskLevel\":\"高风险\",\"evidenceChain\":[\"E001\"]}");

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
        when(reviews.findByCaseIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());
        when(sanctionReviews.findByCustomerIdOrderByCreatedAtAsc("C001")).thenReturn(List.of(sanctionReview));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CaseDossierService service = new CaseDossierService(cases, logs, executions, traces, reviews, snapshots,
                sanctionReviews, mapper);
        CaseDossier first = service.export(7L);
        CaseDossier second = service.export(7L);

        assertThat(first.contentHash()).hasSize(64).isEqualTo(second.contentHash());
        assertThat(first.content().reportParseStatus()).isEqualTo("VALID");
        assertThat(first.content().snapshot().sourceDigest()).isEqualTo("a".repeat(64));
        assertThat(first.content().sanctionReviewHistory()).singleElement()
                .satisfies(review -> assertThat(review.reviewDecision()).isEqualTo("CONFIRM"));
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
}
