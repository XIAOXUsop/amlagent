package com.bank.aml.reporting;

import com.bank.aml.audit.AuditOutboxService;
import com.bank.aml.common.UtcTimestamp;
import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.review.ManualReview;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 可疑交易确认后的报告登记、提交与退回补正状态机。 */
@Service
public class SuspiciousTransactionReportService {

    private static final Pattern EXTERNAL_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{2,127}");

    private final SuspiciousTransactionReportRepository repository;

    private final CaseRepository caseRepository;

    private final AuditOutboxService auditOutbox;

    private final Clock clock;

    public SuspiciousTransactionReportService(SuspiciousTransactionReportRepository repository,
            CaseRepository caseRepository, AuditOutboxService auditOutbox, Clock clock) {
        this.repository = repository;
        this.caseRepository = caseRepository;
        this.auditOutbox = auditOutbox;
        this.clock = clock;
    }

    /** 由人工确认可疑事务内调用，报告理由直接继承已持久化的人工分析记录。 */
    public SuspiciousTransactionReportView openPending(Long caseId, ManualReview review) {
        SuspiciousTransactionReport report = new SuspiciousTransactionReport();
        report.setCaseId(caseId);
        report.setReviewId(review.getId());
        report.setStatus(SuspiciousTransactionReportStatus.PENDING_SUBMISSION);
        report.setReportReason(review.getComment());
        report.setCreatedBy(review.getReviewerId());
        return view(repository.save(report));
    }

    @Transactional(readOnly = true)
    public List<SuspiciousTransactionReportView> pending() {
        return repository.findByStatusInOrderByCreatedAtAsc(Set.of(SuspiciousTransactionReportStatus.PENDING_SUBMISSION,
                SuspiciousTransactionReportStatus.RETURNED_FOR_CORRECTION))
            .stream()
            .map(this::view)
            .toList();
    }

    @Transactional(readOnly = true)
    public SuspiciousTransactionReportView get(Long caseId) {
        return view(requireReport(caseId));
    }

    @Transactional
    public SuspiciousTransactionReportView markSubmitted(Long caseId, int expectedRevision, String externalReference,
            String operator) {
        CaseEntity caseEntity = lockCase(caseId);
        if (caseEntity.getStatus() != CaseStatus.REPORT_PENDING) {
            throw new IllegalStateException("案件不在待报送状态");
        }
        String reference = externalReference == null ? "" : externalReference.trim();
        if (!EXTERNAL_REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("外部报送编号格式非法");
        }
        SuspiciousTransactionReport report = requireReport(caseId);
        if (!Set
            .of(SuspiciousTransactionReportStatus.PENDING_SUBMISSION,
                    SuspiciousTransactionReportStatus.RETURNED_FOR_CORRECTION)
            .contains(report.getStatus()) || report.getRevision() != expectedRevision) {
            throw new IllegalStateException("报告状态或版本已变化，请刷新后重试");
        }
        if (caseRepository.completeSuspiciousReport(caseId, CaseStatus.REPORT_PENDING, CaseStatus.DONE) != 1) {
            throw new IllegalStateException("案件状态已变化，请刷新后重试");
        }
        report.setStatus(SuspiciousTransactionReportStatus.SUBMITTED);
        report.setExternalReference(reference);
        report.setSubmittedBy(operator);
        report.setSubmittedAt(LocalDateTime.now(clock));
        report.setRevision(report.getRevision() + 1);
        report.setReturnReason(null);
        SuspiciousTransactionReport saved = repository.save(report);
        auditOutbox.enqueue("STR_SUBMIT:" + caseId + ":" + expectedRevision, operator, "STR_SUBMITTED", "CASE",
                String.valueOf(caseId), "reportId=" + saved.getId() + ",externalReference=" + reference);
        return view(saved);
    }

    @Transactional
    public SuspiciousTransactionReportView returnForCorrection(Long caseId, int expectedRevision, String reason,
            String operator) {
        CaseEntity caseEntity = lockCase(caseId);
        if (caseEntity.getStatus() != CaseStatus.DONE
                || !"CONFIRM_SUSPICIOUS".equals(caseEntity.getReviewDisposition())) {
            throw new IllegalStateException("仅可退回已报送的可疑案件");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < 10 || normalizedReason.length() > 500) {
            throw new IllegalArgumentException("退回原因需为 10 ~ 500 个字符");
        }
        SuspiciousTransactionReport report = requireReport(caseId);
        if (report.getStatus() != SuspiciousTransactionReportStatus.SUBMITTED
                || report.getRevision() != expectedRevision) {
            throw new IllegalStateException("报告状态或版本已变化，请刷新后重试");
        }
        if (caseRepository.reopenSuspiciousReport(caseId, CaseStatus.DONE, CaseStatus.REPORT_PENDING) != 1) {
            throw new IllegalStateException("案件状态已变化，请刷新后重试");
        }
        report.setStatus(SuspiciousTransactionReportStatus.RETURNED_FOR_CORRECTION);
        report.setReturnedBy(operator);
        report.setReturnedAt(LocalDateTime.now(clock));
        report.setReturnReason(normalizedReason);
        report.setRevision(report.getRevision() + 1);
        SuspiciousTransactionReport saved = repository.save(report);
        auditOutbox.enqueue("STR_RETURN:" + caseId + ":" + expectedRevision, operator, "STR_RETURNED", "CASE",
                String.valueOf(caseId), "reportId=" + saved.getId() + ",reasonLen=" + normalizedReason.length());
        return view(saved);
    }

    private CaseEntity lockCase(Long caseId) {
        return caseRepository.findByIdForUpdate(caseId)
            .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
    }

    private SuspiciousTransactionReport requireReport(Long caseId) {
        return repository.findByCaseId(caseId).orElseThrow(() -> new IllegalArgumentException("可疑交易报告不存在"));
    }

    private SuspiciousTransactionReportView view(SuspiciousTransactionReport report) {
        return new SuspiciousTransactionReportView(report.getId(), report.getCaseId(), report.getReviewId(),
                report.getStatus(), report.getReportReason(), report.getCreatedBy(), report.getRevision(),
                report.getExternalReference(), report.getSubmittedBy(), UtcTimestamp.from(report.getSubmittedAt()),
                report.getReturnedBy(), UtcTimestamp.from(report.getReturnedAt()), report.getReturnReason(),
                UtcTimestamp.from(report.getCreatedAt()), UtcTimestamp.from(report.getUpdatedAt()));
    }

}
