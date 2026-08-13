package com.bank.aml.evaluation;

import com.bank.aml.risk.RiskRule;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleRegressionEvaluatorTest {

    private RuleRegressionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        RiskRuleRepository repository = mock(RiskRuleRepository.class);
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(seedRules());
        evaluator = new RuleRegressionEvaluator(
                new RuleRegressionCaseGenerator(),
                new RiskRuleEngine(repository),
                new ObjectMapper());
    }

    @Test
    void evaluatesOneHundredDeterministicRuleCases() {
        var report = evaluator.run();

        assertThat(report.totalCases()).isEqualTo(100);
        assertThat(report.accuracy()).isEqualTo(100.0);
        assertThat(report.highRiskRecallRate()).isEqualTo(100.0);
        assertThat(report.lowRiskFalsePositiveRate()).isZero();
        assertThat(report.manualReviewTotal()).isEqualTo(20);
        assertThat(report.manualReviewMissCount()).isZero();
    }

    @Test
    void reportContainsNoFabricatedAgentMetrics() throws Exception {
        String json = evaluator.metricsJson(evaluator.run());

        assertThat(json).doesNotContain("structuredOutputSuccessRate");
        assertThat(json).doesNotContain("toolCallSuccessRate");
        assertThat(json).doesNotContain("modelRiskLevel");
        assertThat(json).contains("baselineRiskLevel");
        assertThat(json).contains("manualReviewMissCount");
        assertThat(json).doesNotContain("sanctionMissCount");
    }

    private List<RiskRule> seedRules() {
        return List.of(
                rule("SANCTION_LEVEL_1", "sanction.maxSeverity == 1", "高风险", "MANUAL_REVIEW", 100),
                rule("SANCTION_OTHER", "sanction.maxSeverity >= 2", "高风险", "AUTO_DONE", 90),
                rule("DATA_INCOMPLETE", "transaction.dataComplete == false",
                        "中风险", "MANUAL_REVIEW", 80),
                rule("UBO_UNVERIFIED", "corporate.uboRiskSeverity >= 2",
                        "高风险", "MANUAL_REVIEW", 70),
                rule("TXN_PATTERN_HIGH", "transaction.patternSeverity >= 2",
                        "高风险", "AUTO_DONE", 60),
                rule("TXN_ABNORMAL", "transaction.crossRatio > 20 && transaction.nightRatio > 30"
                                + " && transaction.riskExplained == false",
                        "高风险", "AUTO_DONE", 50),
                rule("UBO_DOCUMENT_INCOMPLETE", "corporate.uboRiskSeverity == 1",
                        "中风险", "AUTO_DONE", 40),
                rule("TXN_MODERATE", "transaction.patternSeverity == 1",
                        "中风险", "AUTO_DONE", 30),
                rule("CROSS_BORDER_MODERATE", "transaction.crossRatio >= 10"
                                + " && transaction.riskExplained == false",
                        "中风险", "AUTO_DONE", 20),
                rule("NIGHT_ACTIVITY_MODERATE", "transaction.nightRatio >= 20"
                                + " && transaction.riskExplained == false",
                        "中风险", "AUTO_DONE", 10));
    }

    private RiskRule rule(String code, String expression, String target, String action, int priority) {
        RiskRule rule = new RiskRule();
        rule.setRuleCode(code);
        rule.setConditionExpression(expression);
        rule.setTargetRiskLevel(target);
        rule.setAction(action);
        rule.setPriority(priority);
        rule.setEnabled(true);
        rule.setVersion(1);
        return rule;
    }
}
