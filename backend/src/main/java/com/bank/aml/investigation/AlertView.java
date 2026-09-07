package com.bank.aml.investigation;

import java.time.LocalDateTime;

public record AlertView(
        Long id,
        String externalAlertId,
        String customerId,
        String ruleCode,
        String scenarioCode,
        String hitReason,
        LocalDateTime occurredAt,
        AlertStatus status,
        Long caseId,
        int revision,
        String resolutionReason,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    static AlertView from(AmlAlert alert) {
        return new AlertView(alert.getId(), alert.getExternalAlertId(), alert.getCustomerId(), alert.getRuleCode(),
                alert.getScenarioCode(), alert.getHitReason(), alert.getOccurredAt(), alert.getStatus(),
                alert.getCaseId(), alert.getRevision(), alert.getResolutionReason(), alert.getCreatedBy(),
                alert.getCreatedAt(), alert.getUpdatedAt());
    }
}
