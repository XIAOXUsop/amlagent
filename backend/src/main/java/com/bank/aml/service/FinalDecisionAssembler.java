package com.bank.aml.service;

import com.bank.aml.agent.AgentReportVocabulary;
import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.risk.RiskRuleEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将模型原始分析与确定性 Guardrail 决策合成为最终对外报告。
 * <p>最终评级、人工复核、动作和结论由本组件统一生成，避免 Guardrail 已上调评级但正文仍保留低风险建议。
 */
@Component
public class FinalDecisionAssembler {

    public DueDiligenceReport assemble(DueDiligenceReport raw,
                                       GuardrailEngine.GuardrailResult guardrail) {
        return assemble(raw, guardrail, false);
    }

    /**
     * @param forceManualReview 上游安全策略强制转人工（例如模型输出违反生产契约）；
     *                          该信号优先于普通 Guardrail 的自动完成结论。
     */
    public DueDiligenceReport assemble(DueDiligenceReport raw,
                                       GuardrailEngine.GuardrailResult guardrail,
                                       boolean forceManualReview) {
        if (raw == null) {
            throw new IllegalArgumentException("raw report must not be null");
        }
        if (guardrail == null || guardrail.decision() == null) {
            throw new IllegalArgumentException("guardrail decision must not be null");
        }

        String finalRisk = guardrail.finalRiskLevel();
        boolean mustEscalate = forceManualReview || guardrail.mustEscalate();
        List<String> findings = mergeFindings(raw.findingCodes(), guardrail.decision().triggeredRules());
        List<String> actions = reconcileActions(guardrail.decision().actionCodes(), finalRisk, mustEscalate,
                guardrail.decision().triggeredRules());
        List<String> riskPoints = mergeRiskPoints(raw.riskPoints(), guardrail);
        String conclusion = renderConclusion(finalRisk, mustEscalate, guardrail.decision().triggeredRules());

        return new DueDiligenceReport(raw.customerId(), raw.customerName(), finalRisk,
                raw.transactionProfile(), raw.corporateProfile(), safe(raw.sanctions()), safe(raw.legalBasis()),
                riskPoints, conclusion, safe(raw.evidenceChain()), mustEscalate, findings, actions);
    }

    private List<String> mergeFindings(List<String> rawFindings,
                                       List<RiskRuleEngine.TriggeredRule> triggeredRules) {
        Set<String> findings = allowed(rawFindings, AgentReportVocabulary.FINDING_CODES);
        for (RiskRuleEngine.TriggeredRule rule : safeRules(triggeredRules)) {
            switch (rule.ruleCode()) {
                case "SANCTION_LEVEL_1" -> findings.add("SANCTION_LEVEL_1_MATCH");
                case "SANCTION_OTHER" -> findings.add("DOMESTIC_WATCHLIST_MATCH");
                case "DATA_INCOMPLETE" -> {
                    findings.add("TRANSACTION_DATA_UNAVAILABLE");
                    findings.add("RISK_ASSESSMENT_UNCERTAIN");
                }
                case "UBO_UNVERIFIED" -> findings.add("UBO_UNVERIFIED");
                case "UBO_DOCUMENT_INCOMPLETE" -> findings.add("UBO_DOCUMENTS_INCOMPLETE");
                case "TXN_ABNORMAL" -> findings.add("NIGHT_CROSS_BORDER_CLUSTER");
                case "CROSS_BORDER_MODERATE" -> findings.add("CROSS_BORDER_ACTIVITY");
                default -> {
                    // 无法与闭集 finding 一一对应的规则只进入 riskPoints，不猜测业务代码。
                }
            }
        }
        return List.copyOf(findings);
    }

    private List<String> reconcileActions(List<String> rawActions, String finalRisk, boolean mustEscalate,
                                          List<RiskRuleEngine.TriggeredRule> triggeredRules) {
        Set<String> actions = allowed(rawActions, AgentReportVocabulary.ACTION_CODES);

        // 高影响底线动作由确定性规则补齐，不依赖模型是否恰好枚举完整。
        for (RiskRuleEngine.TriggeredRule rule : safeRules(triggeredRules)) {
            switch (rule.ruleCode()) {
                case "SANCTION_LEVEL_1" -> {
                    actions.add("FREEZE_ASSETS");
                    actions.add("STOP_FINANCIAL_SERVICE");
                    actions.add("REPORT_TO_AUTHORITY");
                }
                case "SANCTION_OTHER", "TXN_PATTERN_HIGH", "TXN_ABNORMAL" ->
                        actions.add("REVIEW_SUSPICIOUS_TRANSACTION_REPORT");
                case "DATA_INCOMPLETE" -> {
                    actions.add("RETRY_TRANSACTION_SOURCE");
                    actions.add("RESTRICT_AUTOMATED_APPROVAL");
                }
                case "UBO_UNVERIFIED" -> {
                    actions.add("ENHANCED_UBO_VERIFICATION");
                    actions.add("RESTRICT_AUTOMATED_APPROVAL");
                }
                case "UBO_DOCUMENT_INCOMPLETE" -> actions.add("REQUEST_UPDATED_UBO_DOCUMENTS");
                case "TXN_MODERATE" -> actions.add("REFRESH_CUSTOMER_PROFILE");
                default -> {
                    // 不具备一一对应关系的规则只影响风险等级和监测强度。
                }
            }
        }

        // 最终风险不再是低风险时，常规监测与最终决策冲突，必须移除。
        if (!"低风险".equals(finalRisk)) {
            actions.remove("MAINTAIN_STANDARD_MONITORING");
        }
        boolean terminalSanctionDisposition = safeRules(triggeredRules).stream()
                .anyMatch(rule -> "SANCTION_LEVEL_1".equals(rule.ruleCode()));
        boolean transactionSourceUnavailable = safeRules(triggeredRules).stream()
                .anyMatch(rule -> "DATA_INCOMPLETE".equals(rule.ruleCode()));
        if ("高风险".equals(finalRisk) && !terminalSanctionDisposition) {
            actions.add("ENHANCED_DUE_DILIGENCE");
        } else if ("中风险".equals(finalRisk) && !transactionSourceUnavailable) {
            actions.add("INCREASE_MONITORING");
        }
        if (mustEscalate) {
            actions.add("MANUAL_REVIEW");
        } else {
            actions.remove("MANUAL_REVIEW");
        }
        return List.copyOf(actions);
    }

    private List<String> mergeRiskPoints(List<String> rawRiskPoints,
                                         GuardrailEngine.GuardrailResult guardrail) {
        Set<String> points = new LinkedHashSet<>();
        safe(rawRiskPoints).stream().filter(value -> !value.isBlank()).forEach(points::add);
        for (RiskRuleEngine.TriggeredRule rule : safeRules(guardrail.decision().triggeredRules())) {
            points.add("规则 " + rule.ruleCode() + " v" + rule.ruleVersion() + "：" + rule.evidence());
        }
        safe(guardrail.corrections()).stream().filter(value -> !value.isBlank()).forEach(points::add);
        return List.copyOf(points);
    }

    private String renderConclusion(String finalRisk, boolean mustEscalate,
                                    List<RiskRuleEngine.TriggeredRule> rules) {
        String base = switch (finalRisk) {
            case "高风险" -> "基于冻结快照、模型分析与确定性规则校验，最终评定为高风险。建议开展强化尽职调查，并按证据支持的处置代码执行。";
            case "中风险" -> "基于冻结快照、模型分析与确定性规则校验，最终评定为中风险。建议补充核验关键信息并加强持续监测。";
            default -> "基于冻结快照、模型分析与确定性规则校验，最终评定为低风险。建议维持与风险相称的常规持续监测。";
        };
        String review = mustEscalate ? " 本工单必须进入人工复核，自动流程不得直接完成。" : "";
        List<String> codes = safeRules(rules).stream().map(RiskRuleEngine.TriggeredRule::ruleCode).distinct().toList();
        String ruleSummary = codes.isEmpty() ? "" : " 触发规则：" + String.join("、", codes) + "。";
        return base + review + ruleSummary;
    }

    private Set<String> allowed(List<String> values, Set<String> vocabulary) {
        Set<String> result = new LinkedHashSet<>();
        safe(values).stream().filter(vocabulary::contains).forEach(result::add);
        return result;
    }

    private List<String> safe(List<String> values) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        values.stream().filter(java.util.Objects::nonNull).forEach(result::add);
        return List.copyOf(result);
    }

    private List<RiskRuleEngine.TriggeredRule> safeRules(List<RiskRuleEngine.TriggeredRule> rules) {
        return rules == null ? List.of() : rules.stream().filter(java.util.Objects::nonNull).toList();
    }
}
