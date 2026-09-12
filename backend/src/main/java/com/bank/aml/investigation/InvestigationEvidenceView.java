package com.bank.aml.investigation;

import com.bank.aml.common.UtcTimestamp;
import java.time.Instant;

public record InvestigationEvidenceView(Long id, Long hypothesisId, InvestigationEvidenceType evidenceType,
        String evidenceReference, EvidenceStance stance, String findingSummary, String createdBy, Instant createdAt) {
    static InvestigationEvidenceView from(InvestigationEvidenceLink evidence) {
        return new InvestigationEvidenceView(evidence.getId(), evidence.getHypothesisId(), evidence.getEvidenceType(),
                evidence.getEvidenceReference(), evidence.getStance(), evidence.getFindingSummary(),
                evidence.getCreatedBy(), UtcTimestamp.from(evidence.getCreatedAt()));
    }
}
