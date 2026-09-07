package com.bank.aml.review;

import java.time.LocalDateTime;

public record EnhancedDueDiligenceEvidenceView(
        Long id,
        String evidenceId,
        String requiredItemCode,
        String sourceSystem,
        String sourceReference,
        String contentSha256,
        String capturedBy,
        LocalDateTime capturedAt
) {
}
