package com.bank.aml.reporting;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuspiciousTransactionReportServiceTest {
    private final SuspiciousTransactionReportRepository repository =
            mock(SuspiciousTransactionReportRepository.class);
    private final CaseRepository caseRepository = mock(CaseRepository.class);
    private final AuditOutboxService auditOutbox = mock(AuditOutboxService.class);
    private final SuspiciousTransactionReportService service =
            new SuspiciousTransactionReportService(repository, caseRepository, auditOutbox);

    @Test
    void acceptedExternalSubmissionIsTheOnlyTransitionThatCompletesCase() {
        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setStatus(CaseStatus.REPORT_PENDING);
        SuspiciousTransactionReport report = report(SuspiciousTransactionReportStatus.PENDING_SUBMISSION, 0);
        when(caseRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        when(repository.findByCaseId(7L)).thenReturn(Optional.of(report));
        when(caseRepository.completeSuspiciousReport(7L, CaseStatus.REPORT_PENDING, CaseStatus.DONE)).thenReturn(1);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SuspiciousTransactionReportView result = service.markSubmitted(7L, 0, "PBOC:STR/2026-001", "reviewer");

        assertThat(result.status()).isEqualTo(SuspiciousTransactionReportStatus.SUBMITTED);
        assertThat(result.externalReference()).isEqualTo("PBOC:STR/2026-001");
        assertThat(result.revision()).isEqualTo(1);
        verify(caseRepository).completeSuspiciousReport(7L, CaseStatus.REPORT_PENDING, CaseStatus.DONE);
        verify(auditOutbox).enqueue("STR_SUBMIT:7:0", "reviewer", "STR_SUBMITTED", "CASE", "7",
                "reportId=null,externalReference=PBOC:STR/2026-001");
    }

    @Test
    void returnedReportReopensCaseForCorrection() {
        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setStatus(CaseStatus.DONE);
        caseEntity.setReviewDisposition("CONFIRM_SUSPICIOUS");
        SuspiciousTransactionReport report = report(SuspiciousTransactionReportStatus.SUBMITTED, 2);
        when(caseRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        when(repository.findByCaseId(7L)).thenReturn(Optional.of(report));
        when(caseRepository.reopenSuspiciousReport(7L, CaseStatus.DONE, CaseStatus.REPORT_PENDING)).thenReturn(1);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SuspiciousTransactionReportView result = service.returnForCorrection(
                7L, 2, "外部系统退回，需补正交易对手信息", "reviewer");

        assertThat(result.status()).isEqualTo(SuspiciousTransactionReportStatus.RETURNED_FOR_CORRECTION);
        assertThat(result.revision()).isEqualTo(3);
        verify(caseRepository).reopenSuspiciousReport(7L, CaseStatus.DONE, CaseStatus.REPORT_PENDING);
    }

    @Test
    void staleReportRevisionCannotMutateCase() {
        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setStatus(CaseStatus.REPORT_PENDING);
        when(caseRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(caseEntity));
        when(repository.findByCaseId(7L)).thenReturn(Optional.of(
                report(SuspiciousTransactionReportStatus.PENDING_SUBMISSION, 3)));

        assertThatThrownBy(() -> service.markSubmitted(7L, 2, "PBOC:STR/2026-001", "reviewer"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("版本已变化");
    }

    private SuspiciousTransactionReport report(SuspiciousTransactionReportStatus status, int revision) {
        SuspiciousTransactionReport report = new SuspiciousTransactionReport();
        report.setCaseId(7L);
        report.setReviewId(19L);
        report.setStatus(status);
        report.setReportReason("已核验交易路径并确认具有可疑特征");
        report.setCreatedBy("reviewer");
        report.setRevision(revision);
        return report;
    }
}
