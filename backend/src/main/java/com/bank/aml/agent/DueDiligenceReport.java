package com.bank.aml.agent;

import java.util.List;

/**
 * 客户尽调初审报告（AiServices 结构化输出）。
 */
public record DueDiligenceReport(
        String customerId,
        String customerName,
        /** 风险评级：低风险 / 中风险 / 高风险 */
        String riskLevel,
        /** 交易画像摘要 */
        String transactionProfile,
        /** 股权穿透摘要 */
        String corporateProfile,
        /** 制裁黑名单命中 */
        List<String> sanctions,
        /** 法规依据 */
        List<String> legalBasis,
        /** 风险点 */
        List<String> riskPoints,
        /** 结论与建议 */
        String conclusion,
        /** 证据链 */
        List<String> evidenceChain,
        /** 是否必须转人工复核 */
        Boolean manualReviewRequired,
        /** 风险发现代码，只能来自系统提示声明的闭集 */
        List<String> findingCodes,
        /** 后续处置代码，只能来自系统提示声明的闭集 */
        List<String> actionCodes
) {
}
