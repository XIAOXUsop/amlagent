package com.bank.aml.investigation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

public record AlertView(Long id, String externalAlertId, String customerId, String ruleCode, String scenarioCode,
        String hitReason, Instant occurredAt, AlertStatus status, Long caseId, int revision, String resolutionReason,
        String createdBy, Instant createdAt, Instant updatedAt) {
    static AlertView from(AmlAlert alert) {
        return new AlertView(alert.getId(), alert.getExternalAlertId(), alert.getCustomerId(), alert.getRuleCode(),
                alert.getScenarioCode(), alert.getHitReason(), toInstant(alert.getOccurredAt()), alert.getStatus(),
                alert.getCaseId(), alert.getRevision(), alert.getResolutionReason(), alert.getCreatedBy(),
                toInstant(alert.getCreatedAt()), toInstant(alert.getUpdatedAt()));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
