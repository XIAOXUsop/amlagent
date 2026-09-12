package com.bank.aml.explanation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplanationFactInvalidationServiceTest {

    @Test
    void factChangeStalesSubmissionClearsPointerAndAdvancesEpoch() {
        CaseRepository cases = mock(CaseRepository.class);
        ExplanationSubmissionRepository submissions = mock(ExplanationSubmissionRepository.class);
        AlertExplanationUnitRepository units = mock(AlertExplanationUnitRepository.class);
        AuditOutboxService audit = mock(AuditOutboxService.class);
        ExplanationFactInvalidationService service = new ExplanationFactInvalidationService(cases, submissions, units,
                audit);

        CaseEntity caseEntity = new CaseEntity();
        caseEntity.setCaseFactsEpoch(7);
        ExplanationSubmission submission = new ExplanationSubmission();
        setId(submission, 30L);
        submission.setCaseId(1L);
        submission.setUnitId(10L);
        submission.setState(SubmissionState.CURRENT);
        submission.setPayloadJson("{\"outcome\":\"EXPLAINED\"}");
        AlertExplanationUnit unit = new AlertExplanationUnit();
        setId(unit, 10L);
        unit.setCaseId(1L);
        unit.setCurrentSubmissionId(30L);
        unit.setDraftRevision(2);

        when(cases.findByIdForUpdate(1L)).thenReturn(Optional.of(caseEntity));
        when(submissions.findByCaseIdAndStateOrderByIdAsc(1L, SubmissionState.CURRENT)).thenReturn(List.of(submission));
        when(units.findByIdAndCaseId(10L, 1L)).thenReturn(Optional.of(unit));

        service.invalidate(1L, "新增退款事实", "analyst");

        assertThat(submission.getState()).isEqualTo(SubmissionState.STALE);
        assertThat(unit.getCurrentSubmissionId()).isNull();
        assertThat(unit.getDraftJson()).isEqualTo(submission.getPayloadJson());
        assertThat(unit.getDraftRevision()).isEqualTo(3);
        assertThat(caseEntity.getCaseFactsEpoch()).isEqualTo(8);
        verify(submissions).save(submission);
        verify(units).save(unit);
        verify(cases).save(caseEntity);
        verify(audit).enqueue(any(), any(), any(), any(), any(), any());
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

}
