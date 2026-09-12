package com.bank.aml.review;

import com.bank.aml.TestClocks;
import com.bank.aml.TestProperties;
import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.EddTaskPurpose;
import com.bank.aml.domain.ReviewDecision;
import com.bank.aml.security.UserAccount;
import com.bank.aml.security.UserAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnhancedDueDiligenceServiceTest {

    private final EnhancedDueDiligenceRequestRepository repository = mock(EnhancedDueDiligenceRequestRepository.class);

    private final EnhancedDueDiligenceEvidenceRepository evidenceRepository = mock(
            EnhancedDueDiligenceEvidenceRepository.class);

    private final CaseRepository caseRepository = mock(CaseRepository.class);

    private final UserAccountRepository userAccountRepository = mock(UserAccountRepository.class);

    private final AuditOutboxService auditOutbox = mock(AuditOutboxService.class);

    private final EnhancedDueDiligenceService service = new EnhancedDueDiligenceService(repository, evidenceRepository,
            caseRepository, userAccountRepository, auditOutbox, new ObjectMapper(), TestProperties.aml(),
            TestClocks.FIXED);

    @Test
    void createsTrackableRequestWithDeadlineAndRequiredItems() {
        LocalDateTime dueAt = LocalDateTime.now(TestClocks.FIXED).plusDays(5);

        when(userAccountRepository.findByUsername("analyst")).thenReturn(Optional.of(analyst()));
        service.validateReviewDecision(7L, ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE,
                List.of("SOURCE_OF_FUNDS", "SUPPORTING_CONTRACT_INVOICE"), dueAt, "analyst", "反洗钱分析组");
        service.applyReviewDecision(7L, ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE,
                ReviewReasonCode.SOURCE_OF_FUNDS_EVIDENCE_REQUIRED,
                List.of("SOURCE_OF_FUNDS", "SUPPORTING_CONTRACT_INVOICE"), dueAt, "analyst", "反洗钱分析组", "reviewer",
                LocalDateTime.now(TestClocks.FIXED));

        ArgumentCaptor<EnhancedDueDiligenceRequest> captor = ArgumentCaptor.forClass(EnhancedDueDiligenceRequest.class);
        verify(repository).save(captor.capture());
        EnhancedDueDiligenceRequest request = captor.getValue();
        assertThat(request.getCaseId()).isEqualTo(7L);
        assertThat(request.getRoundNo()).isEqualTo(1);
        assertThat(request.getStatus()).isEqualTo(EnhancedDueDiligenceStatus.OPEN);
        assertThat(request.getAssignedTo()).isEqualTo("analyst");
        assertThat(request.getRequiredItemsJson()).contains("SOURCE_OF_FUNDS", "SUPPORTING_CONTRACT_INVOICE");
        assertThat(request.getDueAt()).isEqualTo(dueAt);
    }

    @Test
    void blocksAnyReviewWhileDecisionSupportRequestIsOpen() {
        // v2（§8）：OPEN 的 DECISION_SUPPORT 任务阻断最终处置（CONTINUING_REVIEW 不阻断，
        // 见 EnhancedDueDiligenceContinuationTest）。
        EnhancedDueDiligenceRequest open = request(7L, 1, EnhancedDueDiligenceStatus.OPEN);
        when(repository.findByCaseIdAndStatusOrderByIdAsc(7L, EnhancedDueDiligenceStatus.OPEN))
            .thenReturn(List.of(open));

        assertThatThrownBy(
                () -> service.validateReviewDecision(7L, ReviewDecision.CONFIRM_SUSPICIOUS, null, null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不能再次处置");
    }

    @Test
    void submittedTaskIsNotAutoResolvedWhenNextRoundOpens() {
        // v2（§8.3）：RESOLVED 只能由明确完成产生；原“最近一轮 SUBMITTED 自动 RESOLVED”已移除。
        EnhancedDueDiligenceRequest submitted = request(7L, 2, EnhancedDueDiligenceStatus.SUBMITTED);
        when(repository.findTopByCaseIdOrderByRoundNoDesc(7L)).thenReturn(Optional.of(submitted));

        service.applyReviewDecision(7L, ReviewDecision.REQUEST_ENHANCED_DUE_DILIGENCE,
                ReviewReasonCode.BENEFICIAL_OWNER_VERIFICATION_REQUIRED, List.of("BENEFICIAL_OWNER"),
                LocalDateTime.now(TestClocks.FIXED).plusDays(3), "analyst", "反洗钱分析组", "reviewer",
                LocalDateTime.now(TestClocks.FIXED));

        ArgumentCaptor<EnhancedDueDiligenceRequest> captor = ArgumentCaptor.forClass(EnhancedDueDiligenceRequest.class);
        verify(repository).save(captor.capture());
        assertThat(submitted.getStatus()).isEqualTo(EnhancedDueDiligenceStatus.SUBMITTED);
        assertThat(captor.getValue().getRoundNo()).isEqualTo(3);
        assertThat(captor.getValue().getStatus()).isEqualTo(EnhancedDueDiligenceStatus.OPEN);
        assertThat(captor.getValue().getPurpose()).isEqualTo(EddTaskPurpose.DECISION_SUPPORT);
    }

    @Test
    void analystSubmissionRequiresCaseHoldSummaryAndInternalEvidenceReference() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        when(caseRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(hold));
        EnhancedDueDiligenceRequest submitted = request(7L, 1, EnhancedDueDiligenceStatus.SUBMITTED);
        EnhancedDueDiligenceRequest open = request(7L, 1, EnhancedDueDiligenceStatus.OPEN);
        submitted.setRequiredItemsJson("[\"SOURCE_OF_FUNDS\"]");
        submitted.setEvidenceReferencesJson("[\"EDD-EVIDENCE-31\"]");
        submitted.setResponseSummary("已核验资金来源证明及合同凭证");
        when(repository.findByIdAndCaseId(9L, 7L)).thenReturn(Optional.of(open));
        EnhancedDueDiligenceEvidence saved = mock(EnhancedDueDiligenceEvidence.class);
        when(saved.getId()).thenReturn(31L);
        when(evidenceRepository.saveAllAndFlush(any())).thenReturn(List.of(saved));
        when(repository.submitResponse(eq(9L), eq(7L), eq(EnhancedDueDiligenceStatus.OPEN),
                eq(EnhancedDueDiligenceStatus.SUBMITTED), eq(0), any(), any(), eq("analyst"), any()))
            .thenReturn(1);
        when(repository.findById(9L)).thenReturn(Optional.of(submitted));

        EnhancedDueDiligenceView result = service.submitResponse(7L, 9L, 0, "已核验资金来源证明及合同凭证",
                List.of(evidence("SOURCE_OF_FUNDS")), "analyst", false);

        assertThat(result.status()).isEqualTo(EnhancedDueDiligenceStatus.SUBMITTED);
        assertThat(result.evidenceReferences()).containsExactly("EDD-EVIDENCE-31");

        assertThatThrownBy(() -> service.submitResponse(7L, 9L, 0, "已核验资金来源证明及合同凭证",
                List.of(new EnhancedDueDiligenceEvidenceSubmission("SOURCE_OF_FUNDS", "KYC_PLATFORM", "KYC-2026-001",
                        "bad-hash")),
                "analyst", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SHA-256");
    }

    @Test
    void analystCannotSubmitAnotherAssigneesTask() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        EnhancedDueDiligenceRequest open = request(7L, 1, EnhancedDueDiligenceStatus.OPEN);
        open.setAssignedTo("other-analyst");
        when(caseRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(hold));
        when(repository.findByIdAndCaseId(9L, 7L)).thenReturn(Optional.of(open));

        assertThatThrownBy(() -> service.submitResponse(7L, 9L, 0, "已核验资金来源证明及合同凭证",
                List.of(evidence("SOURCE_OF_FUNDS")), "analyst", false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("承办人");
    }

    @Test
    void corruptedHistoryIsReportedInsteadOfSilentlyDroppingEvidence() {
        EnhancedDueDiligenceRequest corrupted = request(7L, 1, EnhancedDueDiligenceStatus.SUBMITTED);
        corrupted.setEvidenceReferencesJson("not-json");

        assertThatThrownBy(() -> service.view(corrupted)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("历史字段损坏");
    }

    private EnhancedDueDiligenceRequest request(Long caseId, int roundNo, EnhancedDueDiligenceStatus status) {
        EnhancedDueDiligenceRequest request = new EnhancedDueDiligenceRequest();
        request.setCaseId(caseId);
        request.setRoundNo(roundNo);
        request.setReasonCode("SOURCE_OF_FUNDS_EVIDENCE_REQUIRED");
        request.setRequiredItemsJson("[\"SOURCE_OF_FUNDS\"]");
        request.setRequestedBy("reviewer");
        request.setAssignedTo("analyst");
        request.setRequestedAt(LocalDateTime.now(TestClocks.FIXED));
        request.setDueAt(LocalDateTime.now(TestClocks.FIXED).plusDays(5));
        request.setStatus(status);
        return request;
    }

    private EnhancedDueDiligenceEvidenceSubmission evidence(String itemCode) {
        return new EnhancedDueDiligenceEvidenceSubmission(itemCode, "KYC_PLATFORM", "KYC-2026-001", "a".repeat(64));
    }

    private UserAccount analyst() {
        UserAccount account = new UserAccount();
        account.setUsername("analyst");
        account.setRole("ANALYST");
        account.setEnabled(true);
        return account;
    }

}
