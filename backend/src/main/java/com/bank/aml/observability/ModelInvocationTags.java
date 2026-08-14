package com.bank.aml.observability;

/**
 * 模型调用标签：区分不同用途（主 Agent / 摘要 / 评测），使成本指标可按 purpose 维度拆分。
 */
public record ModelInvocationTags(
        String provider,
        String model,
        String purpose
) {
}
