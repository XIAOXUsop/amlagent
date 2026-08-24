package com.bank.aml.assistant.guard;

import java.util.List;

/**
 * 模型生成的结构化法律声明（claim），供服务端逐条校验。
 * <ul>
 *   <li>{@code claimId}：声明编号（如 C1）；</li>
 *   <li>{@code type}：LEGAL_REQUIREMENT / CUSTOMER_FACT / GENERAL_KNOWLEDGE 等；</li>
 *   <li>{@code text}：声明正文；</li>
 *   <li>{@code evidenceIds}：断言所引用的证据（必须属于当前快照）；</li>
 *   <li>{@code supportSpans}：从证据原文摘录的支持片段（必须为原文连续子串）。</li>
 * </ul>
 */
public record AssistantClaim(
        String claimId,
        String type,
        String text,
        List<String> evidenceIds,
        List<String> supportSpans
) {
    public AssistantClaim {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        supportSpans = supportSpans == null ? List.of() : List.copyOf(supportSpans);
        type = type == null ? "" : type.trim();
        text = text == null ? "" : text.trim();
        claimId = claimId == null ? "" : claimId.trim();
    }

    public boolean legalRequirement() {
        return "LEGAL_REQUIREMENT".equals(type);
    }
}