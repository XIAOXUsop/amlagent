package com.bank.aml.agent.guardrail;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.domain.CustomerProfile;
import com.bank.aml.domain.InvestigationSnapshot;
import com.bank.aml.domain.SanctionRecord;
import com.bank.aml.risk.RiskContext;
import com.bank.aml.risk.RiskFactAssembler;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleEngine.TriggeredRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Guardrails 安全护栏：由配置化风险规则（{@code risk_rule} 表）驱动，
 * 独立于 LLM 校验并强制修正风险评级，输出可解释决策。
 * <p>核心原则：大模型负责理解推理总结，确定性规则系统掌握最终风险决策权。
 * 结构化风险事实由 {@link RiskFactAssembler} 从工具结果统一组装，不依赖模型声明。
 */
@Component
public class GuardrailEngine {

    private final RiskFactAssembler riskFactAssembler;
    private final RiskRuleEngine ruleEngine;

    public GuardrailEngine(RiskFactAssembler riskFactAssembler, RiskRuleEngine ruleEngine) {
        this.riskFactAssembler = riskFactAssembler;
        this.ruleEngine = ruleEngine;
    }

    /** 决策结果（可解释） */
    public record GuardrailDecision(
            String modelRiskLevel,
            String finalRiskLevel,
            List<TriggeredRule> triggeredRules,
            String requiredAction,
            /** 最终处置代码（模型输出 + 规则补充，已去重） */
            List<String> actionCodes
    ) {
    }

    /** 护栏校验结果 */
    public record GuardrailResult(
            String finalRiskLevel,
            List<String> corrections,
            boolean mustEscalate,
            List<SanctionRecord> sanctionHits,
            GuardrailDecision decision
    ) {
    }

    /** 生产链路入口：从客户数据源组装风险事实后执行护栏决策 */
    public GuardrailResult apply(CustomerProfile customer, DueDiligenceReport report) {
        String modelLevel = report.riskLevel() == null ? "低风险" : report.riskLevel();
        RiskContext ctx = riskFactAssembler.assemble(customer, modelLevel);
        return applyRules(ctx, report, riskFactAssembler.searchSanctions(customer));
    }

    /**
     * 快照入口：从已冻结的尽调快照提取风险事实执行护栏决策，不二次读取数据源。
     * <p>Agent 推理与 Guardrails 校验因此共享同一份数据事实，避免数据源在两者之间变化导致的不一致。
     */
    public GuardrailResult apply(InvestigationSnapshot snapshot, DueDiligenceReport report) {
        String modelLevel = report.riskLevel() == null ? "低风险" : report.riskLevel();
        RiskContext facts = snapshot.riskFacts();
        RiskContext effectiveContext = new RiskContext(
                facts.maxSeverity(), facts.sanctionHit(), facts.crossRatio(), facts.nightRatio(),
                facts.largeCount(), facts.transactionDataComplete(), facts.transactionRiskExplained(),
                facts.transactionPatternSeverity(), facts.uboRiskSeverity(), modelLevel, levelCode(modelLevel));
        return applyRules(effectiveContext, report, snapshot.sanctionHits());
    }

    /**
     * 使用已经聚合好的风险事实执行护栏决策。
     * <p>该入口不访问数据源，适用于离线评测、回放和单元测试。
     */
    public GuardrailResult apply(RiskContext context, DueDiligenceReport report) {
        String modelLevel = report.riskLevel() == null ? "低风险" : report.riskLevel();
        RiskContext effectiveContext = new RiskContext(
                context.maxSeverity(),
                context.sanctionHit(),
                context.crossRatio(),
                context.nightRatio(),
                context.largeCount(),
                context.transactionDataComplete(),
                context.transactionRiskExplained(),
                context.transactionPatternSeverity(),
                context.uboRiskSeverity(),
                modelLevel,
                levelCode(modelLevel));
        return applyRules(effectiveContext, report, List.of());
    }

    private GuardrailResult applyRules(RiskContext context,
                                       DueDiligenceReport report,
                                       List<SanctionRecord> sanctionHits) {
        String modelLevel = context.modelRiskLevel();
        List<TriggeredRule> triggered = ruleEngine.evaluate(context);

        String finalRisk = modelLevel;
        // 护栏只能追加处置或上调风险，不能取消模型已经明确要求的人工复核。
        boolean mustEscalate = Boolean.TRUE.equals(report.manualReviewRequired());
        List<String> corrections = new ArrayList<>();
        for (TriggeredRule rule : triggered) {
            if (levelCode(rule.targetRiskLevel()) > levelCode(finalRisk)) {
                corrections.add("Guardrails【" + rule.ruleCode() + " v" + rule.ruleVersion() + "】："
                        + rule.evidence() + "，评级由【" + finalRisk + "】上调为【" + rule.targetRiskLevel() + "】");
                finalRisk = rule.targetRiskLevel();
            }
            if ("MANUAL_REVIEW".equals(rule.action())) {
                mustEscalate = true;
                corrections.add("Guardrails【" + rule.ruleCode() + "】：命中底线规则，必须转人工复核");
            }
        }

        // 最终处置代码：模型输出 + 规则补充（转人工时强制含 MANUAL_REVIEW），去重
        Set<String> actionCodes = new LinkedHashSet<>();
        if (report.actionCodes() != null) {
            actionCodes.addAll(report.actionCodes());
        }
        if (mustEscalate) {
            actionCodes.add("MANUAL_REVIEW");
        }

        String requiredAction = mustEscalate ? "MANUAL_REVIEW" : "AUTO_DONE";
        GuardrailDecision decision = new GuardrailDecision(modelLevel, finalRisk, triggered,
                requiredAction, List.copyOf(actionCodes));
        return new GuardrailResult(finalRisk, corrections, mustEscalate, sanctionHits, decision);
    }

    private int levelCode(String level) {
        return switch (level) {
            case "高风险" -> 3;
            case "中风险" -> 2;
            default -> 1;
        };
    }
}
