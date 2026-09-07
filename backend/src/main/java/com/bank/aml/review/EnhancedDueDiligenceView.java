package com.bank.aml.review;

import java.time.LocalDateTime;
import java.util.List;

/** 对前端和调查档案暴露的补充尽调视图，不包含附件正文。 */
public record EnhancedDueDiligenceView(
        Long id,
        Long caseId,
        int roundNo,
        String reasonCode,
        List<String> requiredItems,
        String requestedBy,
        LocalDateTime requestedAt,
        String assignedTo,
        String assignedUnit,
        LocalDateTime dueAt,
        EnhancedDueDiligenceStatus status,
        boolean overdue,
        int revision,
        String responseSummary,
        List<String> evidenceReferences,
        String respondedBy,
        LocalDateTime respondedAt,
        LocalDateTime resolvedAt,
        String cancelledBy,
        LocalDateTime cancelledAt,
        String cancellationReason,
        List<EnhancedDueDiligenceEvidenceView> evidenceItems
) {
}
