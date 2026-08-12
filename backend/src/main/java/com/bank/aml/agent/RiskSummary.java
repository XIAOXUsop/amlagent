package com.bank.aml.agent;

/**
 * 风险评级摘要（AiServices 结构化输出验证用）。
 */
public record RiskSummary(
        /** 风险评级：低风险 / 中风险 / 高风险 */
        String riskLevel,
        /** 摘要说明 */
        String summary
) {
}
