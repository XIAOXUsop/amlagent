package com.bank.aml.review;

/** 分析员提交的证据元数据；服务端验证材料覆盖、来源类型和 SHA-256。 */
public record EnhancedDueDiligenceEvidenceSubmission(
        String requiredItemCode,
        String sourceSystem,
        String sourceReference,
        String contentSha256
) {
}
