package com.bank.aml.agent;

import java.util.List;

/**
 * 模型可生成的尽调分析结果。
 * <p>该边界故意不包含 customerId/customerName 等可信身份字段；最终报告身份只能由后端冻结快照补充。
 */
public record AgentAnalysis(
        String riskLevel,
        String transactionProfile,
        String corporateProfile,
        List<String> sanctions,
        List<String> legalBasis,
        List<String> riskPoints,
        String conclusion,
        List<String> evidenceChain,
        Boolean manualReviewRequired,
        List<String> findingCodes,
        List<String> actionCodes
) {
}
