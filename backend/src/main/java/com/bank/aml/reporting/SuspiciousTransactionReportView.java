package com.bank.aml.reporting;

import java.time.LocalDateTime;

public record SuspiciousTransactionReportView(
        Long id, Long caseId, Long reviewId, SuspiciousTransactionReportStatus status,
        String reportReason, String createdBy, int revision, String externalReference,
        String submittedBy, LocalDateTime submittedAt, String returnedBy,
        LocalDateTime returnedAt, String returnReason, LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
