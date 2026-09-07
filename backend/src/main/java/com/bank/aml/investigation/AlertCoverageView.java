package com.bank.aml.investigation;

import java.time.LocalDateTime;

public record AlertCoverageView(
        Long id,
        Long alertId,
        Long caseId,
        Long hypothesisId,
        /** 覆盖决定形成时锁定的假设版本；NULL 表示存量数据，门禁会要求重新确认。 */
        Long hypothesisRevision,
        AlertCoverageConclusion conclusion,
        String analysisSummary,
        int revision,
        String updatedBy,
        LocalDateTime updatedAt
) {
    static AlertCoverageView from(AlertInvestigationCoverage coverage) {
        return new AlertCoverageView(coverage.getId(), coverage.getAlertId(), coverage.getCaseId(),
                coverage.getHypothesisId(), coverage.getHypothesisRevision(), coverage.getConclusion(),
                coverage.getAnalysisSummary(), coverage.getRevision(), coverage.getUpdatedBy(),
                coverage.getUpdatedAt());
    }
}
