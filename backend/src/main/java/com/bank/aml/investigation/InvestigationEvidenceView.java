package com.bank.aml.investigation;

import java.time.LocalDateTime;

public record InvestigationEvidenceView(
        Long id,
        Long hypothesisId,
        InvestigationEvidenceType evidenceType,
        String evidenceReference,
        EvidenceStance stance,
        String findingSummary,
        String createdBy,
        LocalDateTime createdAt
) {
    static InvestigationEvidenceView from(InvestigationEvidenceLink evidence) {
        return new InvestigationEvidenceView(evidence.getId(), evidence.getHypothesisId(), evidence.getEvidenceType(),
                evidence.getEvidenceReference(), evidence.getStance(), evidence.getFindingSummary(),
                evidence.getCreatedBy(), evidence.getCreatedAt());
    }
}
