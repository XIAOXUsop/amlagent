package com.bank.aml.service;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.risk.RiskRuleEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalDecisionAssemblerTest {

    private final FinalDecisionAssembler assembler = new FinalDecisionAssembler();

    @Test
    void rewritesLowRiskNarrativeWhenGuardrailRaisesToHigh() {
        DueDiligenceReport raw = report("低风险", false,
                List.of("MAINTAIN_STANDARD_MONITORING"), List.of("NO_SANCTION_HIT"),
                "建议维持常规监测");
        GuardrailEngine.GuardrailResult guardrail = guardrail("高风险", true,
                List.of(rule("SANCTION_LEVEL_1", "命中一级制裁")),
                List.of("MAINTAIN_STANDARD_MONITORING", "MANUAL_REVIEW"));

        DueDiligenceReport result = assembler.assemble(raw, guardrail);

        assertThat(result.riskLevel()).isEqualTo("高风险");
        assertThat(result.conclusion()).contains("最终评定为高风险", "必须进入人工复核", "SANCTION_LEVEL_1")
                .doesNotContain("建议维持常规监测");
        assertThat(result.actionCodes()).contains("MANUAL_REVIEW")
                .doesNotContain("MAINTAIN_STANDARD_MONITORING", "ENHANCED_DUE_DILIGENCE");
        assertThat(result.actionCodes()).contains("FREEZE_ASSETS", "STOP_FINANCIAL_SERVICE", "REPORT_TO_AUTHORITY");
        assertThat(result.findingCodes()).contains("SANCTION_LEVEL_1_MATCH");
    }

    @Test
    void removesUnknownCodesAndDeduplicatesDefensively() {
        DueDiligenceReport raw = report("低风险", false,
                List.of("MAINTAIN_STANDARD_MONITORING", "MADE_UP", "MAINTAIN_STANDARD_MONITORING"),
                List.of("NORMAL_TRANSACTION_PATTERN", "MADE_UP"), "raw");
        GuardrailEngine.GuardrailResult guardrail = guardrail("低风险", false, List.of(),
                List.of("MAINTAIN_STANDARD_MONITORING", "MADE_UP"));

        DueDiligenceReport result = assembler.assemble(raw, guardrail);

        assertThat(result.actionCodes()).containsExactly("MAINTAIN_STANDARD_MONITORING");
        assertThat(result.findingCodes()).containsExactly("NORMAL_TRANSACTION_PATTERN");
        assertThat(result.manualReviewRequired()).isFalse();
    }

    @Test
    void mediumRiskCannotKeepStandardMonitoringAndGetsEnhancedMonitoring() {
        DueDiligenceReport raw = report("低风险", false,
                List.of("MAINTAIN_STANDARD_MONITORING"), List.of("CROSS_BORDER_ACTIVITY"), "raw");
        GuardrailEngine.GuardrailResult guardrail = guardrail("中风险", false,
                List.of(rule("CROSS_BORDER_MODERATE", "跨境比例 20%")),
                List.of("MAINTAIN_STANDARD_MONITORING"));

        DueDiligenceReport result = assembler.assemble(raw, guardrail);

        assertThat(result.actionCodes()).containsExactly("INCREASE_MONITORING");
        assertThat(result.conclusion()).contains("最终评定为中风险");
        assertThat(result.riskPoints()).anyMatch(value -> value.contains("CROSS_BORDER_MODERATE"));
    }

    @Test
    void forcedSafetyHoldCannotBeDowngradedToAutomaticCompletion() {
        DueDiligenceReport safetyReport = report("中风险", true,
                List.of("MANUAL_REVIEW", "INCREASE_MONITORING"),
                List.of("RISK_ASSESSMENT_UNCERTAIN"), "模型输出契约不合规");
        GuardrailEngine.GuardrailResult ordinaryGuardrail = guardrail("中风险", false,
                List.of(), List.of("INCREASE_MONITORING"));

        DueDiligenceReport result = assembler.assemble(safetyReport, ordinaryGuardrail, true);

        assertThat(result.manualReviewRequired()).isTrue();
        assertThat(result.actionCodes()).contains("MANUAL_REVIEW", "INCREASE_MONITORING");
        assertThat(result.conclusion()).contains("必须进入人工复核");
    }

    @Test
    void deterministicRulesCompleteMandatoryOperationalActions() {
        DueDiligenceReport raw = report("低风险", false,
                List.of("MAINTAIN_STANDARD_MONITORING"), List.of("NO_SANCTION_HIT"), "raw");

        DueDiligenceReport missingData = assembler.assemble(raw, guardrail("中风险", true,
                List.of(rule("DATA_INCOMPLETE", "交易源不可用")), List.of("MANUAL_REVIEW")));
        DueDiligenceReport ubo = assembler.assemble(raw, guardrail("高风险", true,
                List.of(rule("UBO_UNVERIFIED", "受益所有人无法核实")), List.of("MANUAL_REVIEW")));
        DueDiligenceReport suspiciousPattern = assembler.assemble(raw, guardrail("高风险", false,
                List.of(rule("TXN_PATTERN_HIGH", "高严重度模式")), List.of()));

        assertThat(missingData.actionCodes()).contains(
                "MANUAL_REVIEW", "RETRY_TRANSACTION_SOURCE", "RESTRICT_AUTOMATED_APPROVAL")
                .doesNotContain("INCREASE_MONITORING");
        assertThat(ubo.actionCodes()).contains(
                "MANUAL_REVIEW", "ENHANCED_UBO_VERIFICATION", "RESTRICT_AUTOMATED_APPROVAL", "ENHANCED_DUE_DILIGENCE");
        assertThat(suspiciousPattern.actionCodes()).contains(
                "ENHANCED_DUE_DILIGENCE", "REVIEW_SUSPICIOUS_TRANSACTION_REPORT");
    }

    @Test
    void refusesMissingDecisionInputs() {
        assertThatThrownBy(() -> assembler.assemble(null, guardrail("低风险", false, List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> assembler.assemble(report("低风险", false, List.of(), List.of(), "raw"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toleratesMissingOptionalGuardrailCollections() {
        GuardrailEngine.GuardrailDecision decision = new GuardrailEngine.GuardrailDecision(
                "低风险", "低风险", null, "AUTO_DONE", List.of("MAINTAIN_STANDARD_MONITORING"));
        GuardrailEngine.GuardrailResult guardrail = new GuardrailEngine.GuardrailResult(
                "低风险", null, false, List.of(), decision);

        DueDiligenceReport result = assembler.assemble(
                report("低风险", false, List.of("MAINTAIN_STANDARD_MONITORING"),
                        List.of("NORMAL_TRANSACTION_PATTERN"), "raw"), guardrail);

        assertThat(result.riskLevel()).isEqualTo("低风险");
        assertThat(result.conclusion()).contains("最终评定为低风险");
    }

    private DueDiligenceReport report(String risk, boolean manual, List<String> actions,
                                      List<String> findings, String conclusion) {
        return new DueDiligenceReport("C001", "可信客户", risk, "tx", "corp", List.of(),
                List.of("LEGAL-1"), List.of("原始风险点"), conclusion, List.of("LEGAL-1"), manual,
                findings, actions);
    }

    private GuardrailEngine.GuardrailResult guardrail(String risk, boolean manual,
                                                      List<RiskRuleEngine.TriggeredRule> rules,
                                                      List<String> actions) {
        GuardrailEngine.GuardrailDecision decision = new GuardrailEngine.GuardrailDecision(
                "低风险", risk, rules, manual ? "MANUAL_REVIEW" : "AUTO_DONE", actions);
        return new GuardrailEngine.GuardrailResult(risk, List.of("评级修正"), manual, List.of(), decision);
    }

    private RiskRuleEngine.TriggeredRule rule(String code, String evidence) {
        return new RiskRuleEngine.TriggeredRule(code, 1, "高风险", "MANUAL_REVIEW", evidence);
    }
}
