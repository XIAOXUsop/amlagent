package com.bank.aml.assistant.domain;

/** 模型可引用的安全证据摘要；raw payload 不通过 factId 反查给前端。 */
public record AssistantEvidence(String evidenceId, EvidenceType type, String title, String summary, String source) {
    public enum EvidenceType {
        CUSTOMER_PROFILE, TRANSACTION_AGGREGATE, OWNERSHIP, SANCTION, AML_LEGAL, BANKING_PUBLIC
    }
}
