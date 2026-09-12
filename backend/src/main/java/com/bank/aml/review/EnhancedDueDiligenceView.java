package com.bank.aml.review;

import java.time.Instant;
import java.util.List;

/** 对前端和调查档案暴露的补充尽调视图，不包含附件正文。 */
public record EnhancedDueDiligenceView(Long id, Long caseId, int roundNo, String reasonCode, List<String> requiredItems,
        String requestedBy, Instant requestedAt, String assignedTo, String assignedUnit, Instant dueAt,
        EnhancedDueDiligenceStatus status, boolean overdue, int revision, String responseSummary,
        List<String> evidenceReferences, String respondedBy, Instant respondedAt, String resolvedBy, Instant resolvedAt,
        String cancelledBy, Instant cancelledAt, String cancellationReason,
        List<EnhancedDueDiligenceEvidenceView> evidenceItems) {
}
