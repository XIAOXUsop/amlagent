package com.bank.aml.evaluation;

import java.util.List;

/**
 * 独立维护的 Agent 评测数据集。
 *
 * <p>案例与规则回归生成器完全分离，事实、标签和评分要求保存在版本化 JSON 中。
 * 当前首版属于 AI 辅助整理的合成案例，完成领域专家复核前不得称为专家金标数据。
 */
public record AgentEvalDataset(
        String datasetId,
        String version,
        String description,
        String sourceType,
        String annotationMethod,
        String reviewStatus,
        List<AgentEvalCase> cases
) {

    public record AgentEvalCase(
            String id,
            String split,
            String scenario,
            String difficulty,
            AgentInput input,
            ToolFixture toolFixture,
            ExpectedOutcome expected,
            Annotation annotation
    ) {
    }

    /** 只向 Agent 暴露工单身份和预警，不提前泄漏工具查询结果。 */
    public record AgentInput(
            String customerId,
            String customerName,
            String identityNumber,
            String customerType,
            String asOfDate,
            String alertDescription,
            String caseDescription
    ) {
    }

    /** 后续真实 Agent 评测使用的逐案例工具快照。 */
    public record ToolFixture(
            String transactionResult,
            String corporateResult,
            String sanctionResult,
            String legalQuery,
            List<String> legalQueryTerms,
            String legalResult,
            RiskFacts riskFacts
    ) {
    }

    /** Guardrails 的结构化事实输入，属于案例夹具而非期望标签。 */
    public record RiskFacts(
            double crossBorderRatio,
            double nightTransactionRatio,
            long largeTransactionCount,
            boolean transactionDataComplete,
            boolean transactionRiskExplained,
            int transactionPatternSeverity,
            int uboRiskSeverity,
            boolean sanctionHit,
            int maxSanctionSeverity
    ) {
    }

    /** 独立标注的期望结果；不由 RiskRuleEngine 自动生成。 */
    public record ExpectedOutcome(
            String riskLevel,
            boolean mustEscalate,
            List<String> requiredTools,
            List<String> requiredRiskSignals,
            List<String> requiredFindingCodes,
            List<String> allowedFindingCodes,
            List<String> requiredActions,
            List<String> allowedActions,
            List<String> acceptableLegalTopics,
            List<String> forbiddenClaimCodes
    ) {
    }

    public record Annotation(
            String rationale,
            List<String> factReferences,
            String reviewStatus,
            String reviewerNote
    ) {
    }
}
