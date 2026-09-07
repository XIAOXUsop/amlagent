package com.bank.aml.investigation;

import java.time.LocalDateTime;
import java.util.List;

public record InvestigationHypothesisView(
        Long id,
        Long caseId,
        String scenarioCode,
        String hypothesisCode,
        String title,
        String investigationQuestion,
        List<InvestigationEvidenceType> requiredEvidenceTypes,
        HypothesisStatus status,
        String rationale,
        int revision,
        String createdBy,
        String updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<InvestigationEvidenceView> evidence
) { }
