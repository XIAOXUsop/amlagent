package com.bank.aml.agent.guardrail;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.datasource.mock.MockDataSource;
import com.bank.aml.risk.RiskContext;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleEngine.TriggeredRule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Guardrails 安全护栏：由配置化风险规则（{@code risk_rule} 表）驱动，
 * 独立于 LLM 校验并强制修正风险评级，输出可解释决策。
 * <p>核心原则：大模型负责理解推理总结，确定性规则系统掌握最终风险决策权。
 */
@Component
public class GuardrailEngine {

    private final MockDataSource dataSource;
    private final RiskRuleEngine ruleEngine;

    public GuardrailEngine(MockDataSource dataSource, RiskRuleEngine ruleEngine) {
        this.dataSource = dataSource;
        this.ruleEngine = ruleEngine;
    }

    /** 决策结果（可解释） */
    public record GuardrailDecision(
            String modelRiskLevel,
            String finalRiskLevel,
            List<TriggeredRule> triggeredRules,
            String requiredAction
    ) {
    }

    /** 护栏校验结果 */
    public record GuardrailResult(
            String finalRiskLevel,
            List<String> corrections,
            boolean mustEscalate,
            List<MockDataSource.SanctionEntry> sanctionHits,
            GuardrailDecision decision
    ) {
    }

    public GuardrailResult apply(MockDataSource.Customer customer, DueDiligenceReport report) {
        List<MockDataSource.SanctionEntry> hits = searchSanctions(customer);
        int maxSeverity = hits.stream().mapToInt(MockDataSource.SanctionEntry::severity).max().orElse(0);
        boolean sanctionHit = !hits.isEmpty();

        // 交易聚合
        var txns = dataSource.transactionsOf(customer.id());
        long night = txns.stream().filter(t -> isNight(t.date())).count();
        long cross = txns.stream().filter(t -> t.country().isCrossBorder()).count();
        long large = txns.stream().filter(t -> t.amount().compareTo(MILLION) >= 0).count();
        double nightRatio = txns.isEmpty() ? 0 : 100.0 * night / txns.size();
        double crossRatio = txns.isEmpty() ? 0 : 100.0 * cross / txns.size();

        String modelLevel = report.riskLevel() == null ? "低风险" : report.riskLevel();
        RiskContext ctx = new RiskContext(maxSeverity, sanctionHit, crossRatio, nightRatio,
                large, modelLevel, levelCode(modelLevel));

        List<TriggeredRule> triggered = ruleEngine.evaluate(ctx);

        String finalRisk = modelLevel;
        boolean mustEscalate = false;
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

        String requiredAction = mustEscalate ? "MANUAL_REVIEW" : "AUTO_DONE";
        GuardrailDecision decision = new GuardrailDecision(modelLevel, finalRisk, triggered, requiredAction);
        return new GuardrailResult(finalRisk, corrections, mustEscalate, hits, decision);
    }

    private List<MockDataSource.SanctionEntry> searchSanctions(MockDataSource.Customer customer) {
        List<MockDataSource.SanctionEntry> hits = new ArrayList<>(dataSource.searchSanctions(customer.name()));
        if (customer.idCard() != null && !customer.idCard().isBlank()) {
            hits.addAll(dataSource.searchSanctions(customer.idCard()));
        }
        return hits.stream().distinct().toList();
    }

    private boolean isNight(java.time.LocalDateTime date) {
        int hour = date.getHour();
        return hour >= 22 || hour < 6;
    }

    private int levelCode(String level) {
        return switch (level) {
            case "高风险" -> 3;
            case "中风险" -> 2;
            default -> 1;
        };
    }

    private static final java.math.BigDecimal MILLION = new java.math.BigDecimal("1000000");
}
