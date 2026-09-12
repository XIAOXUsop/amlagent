package com.bank.aml.explanation;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 案件关键事实变化的统一失效入口。
 *
 * <p>
 * 退款、Claim 等事实一旦变化，既有 CURRENT 提交的冻结依据便不再代表当前事实。 本服务在案件锁内推进事实 epoch、将提交置为
 * STALE，并清除单元当前指针；原 payload 回填为草稿，要求分析员在新事实基础上重新评估，而不是复用旧结论重新提交。
 */
@Service
public class ExplanationFactInvalidationService {

    private final CaseRepository caseRepository;

    private final ExplanationSubmissionRepository submissionRepository;

    private final AlertExplanationUnitRepository unitRepository;

    private final AuditOutboxService auditOutbox;

    public ExplanationFactInvalidationService(CaseRepository caseRepository,
            ExplanationSubmissionRepository submissionRepository, AlertExplanationUnitRepository unitRepository,
            AuditOutboxService auditOutbox) {
        this.caseRepository = caseRepository;
        this.submissionRepository = submissionRepository;
        this.unitRepository = unitRepository;
        this.auditOutbox = auditOutbox;
    }

    @Transactional
    public void invalidate(Long caseId, String reason, String actor) {
        CaseEntity caseEntity = caseRepository.findByIdForUpdate(caseId)
            .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        String normalizedReason = reason == null || reason.isBlank() ? "关键事实发生变化" : reason.trim();

        for (ExplanationSubmission submission : submissionRepository.findByCaseIdAndStateOrderByIdAsc(caseId,
                SubmissionState.CURRENT)) {
            submission.setState(SubmissionState.STALE);
            submission.setSupersededReason(normalizedReason);
            submissionRepository.save(submission);

            unitRepository.findByIdAndCaseId(submission.getUnitId(), caseId).ifPresent(unit -> {
                if (submission.getId().equals(unit.getCurrentSubmissionId())) {
                    unit.setCurrentSubmissionId(null);
                    unit.setDraftJson(submission.getPayloadJson());
                    unit.setDraftRevision(unit.getDraftRevision() + 1);
                    unitRepository.save(unit);
                }
            });
        }
        caseEntity.setCaseFactsEpoch(caseEntity.getCaseFactsEpoch() + 1);
        caseRepository.save(caseEntity);
        auditOutbox.enqueue("EXPLANATION_FACT_CHANGE:" + caseId + ":" + caseEntity.getCaseFactsEpoch(), actor,
                "EXPLANATION_FACT_INVALIDATE", "CASE", String.valueOf(caseId), normalizedReason);
    }

}
