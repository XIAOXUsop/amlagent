package com.bank.aml.reporting;

import java.time.Instant;

public record SuspiciousTransactionReportView(Long id, Long caseId, Long reviewId,
        SuspiciousTransactionReportStatus status, String reportReason, String createdBy, int revision,
        String externalReference, String submittedBy, Instant submittedAt, String returnedBy, Instant returnedAt,
        String returnReason, Instant createdAt, Instant updatedAt) {
}
