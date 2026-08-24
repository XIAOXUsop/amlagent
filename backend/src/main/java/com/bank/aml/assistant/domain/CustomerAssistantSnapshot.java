package com.bank.aml.assistant.domain;

import java.time.Instant;
import java.util.List;

/** 单次助手 run 的不可变、脱敏事实快照。 */
public record CustomerAssistantSnapshot(
        String snapshotId,
        String conversationId,
        String runId,
        Instant asOfTime,
        AssistantCustomerView customer,
        TransactionRiskView transactionRisk,
        OwnershipRiskView ownershipRisk,
        SanctionRiskView sanctionRisk,
        List<AssistantEvidence> evidence,
        String sourceSystem,
        String sourceVersion,
        String knowledgeIndexVersion,
        String sourceDigest
) {
    public CustomerAssistantSnapshot {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public boolean ownsEvidence(String evidenceId) {
        return evidenceId != null && evidence.stream().anyMatch(item -> evidenceId.equals(item.evidenceId()));
    }
}
