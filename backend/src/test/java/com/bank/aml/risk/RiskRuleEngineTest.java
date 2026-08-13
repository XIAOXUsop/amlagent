package com.bank.aml.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskRuleEngineTest {

    private RiskRuleRepository repository;
    private RiskRuleEngine engine;

    @BeforeEach
    void setUp() {
        repository = mock(RiskRuleRepository.class);
        engine = new RiskRuleEngine(repository);
    }

    @Test
    void levelOneSanctionDoesNotMatchOtherSanctionRule() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(
                rule("SANCTION_LEVEL_1", "sanction.maxSeverity == 1", "高风险", "MANUAL_REVIEW", 10),
                rule("SANCTION_OTHER", "sanction.maxSeverity >= 2", "高风险", "AUTO_DONE", 20)));

        assertThat(engine.evaluate(context(1, true, 0, 0, true, false, 0, 0)))
                .extracting(RiskRuleEngine.TriggeredRule::ruleCode)
                .containsExactly("SANCTION_LEVEL_1");
    }

    @Test
    void unexplainedCrossBorderAndNightActivityMatchesAndExpression() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(
                rule("TXN_ABNORMAL",
                        "transaction.crossRatio > 20 && transaction.nightRatio > 30 && transaction.riskExplained == false",
                        "高风险", "AUTO_DONE", 60)));

        assertThat(engine.evaluate(context(0, false, 25, 40, true, false, 0, 0))).hasSize(1);
        assertThat(engine.evaluate(context(0, false, 25, 40, true, true, 0, 0))).isEmpty();
        assertThat(engine.evaluate(context(0, false, 10, 40, true, false, 0, 0))).isEmpty();
    }

    @Test
    void evaluatesStructuredTransactionAndUboFacts() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(
                rule("TXN_PATTERN_HIGH", "transaction.patternSeverity >= 2", "高风险", "AUTO_DONE", 50),
                rule("UBO_UNVERIFIED", "corporate.uboRiskSeverity >= 2", "高风险", "MANUAL_REVIEW", 40)));

        assertThat(engine.evaluate(context(0, false, 0, 0, true, false, 2, 2)))
                .extracting(RiskRuleEngine.TriggeredRule::ruleCode)
                .containsExactly("TXN_PATTERN_HIGH", "UBO_UNVERIFIED");
    }

    @Test
    void evaluatesBooleanDataCompletenessFact() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(
                rule("DATA_INCOMPLETE", "transaction.dataComplete == false", "中风险", "MANUAL_REVIEW", 30)));

        assertThat(engine.evaluate(context(0, false, 0, 0, false, false, 0, 0))).hasSize(1);
        assertThat(engine.evaluate(context(0, false, 0, 0, true, false, 0, 0))).isEmpty();
    }

    @Test
    void evaluatesOrExpression() {
        assertThat(engine.evaluate(
                "transaction.patternSeverity == 1 || corporate.uboRiskSeverity == 1",
                context(0, false, 0, 0, true, false, 0, 1))).isTrue();
        assertThat(engine.evaluate(
                "transaction.patternSeverity == 1 || corporate.uboRiskSeverity == 1",
                context(0, false, 0, 0, true, false, 0, 0))).isFalse();
    }

    @Test
    void rejectsUnknownFieldInsteadOfTreatingItAsZero() {
        assertThatThrownBy(() -> engine.evaluate(
                "transaction.patternSeverty == 0",
                context(0, false, 0, 0, true, false, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported risk rule field");
    }

    @Test
    void rejectsIllegalValueInsteadOfTreatingItAsZero() {
        assertThatThrownBy(() -> engine.evaluate(
                "transaction.patternSeverity == severe",
                context(0, false, 0, 0, true, false, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid risk rule value");
    }

    @Test
    void rejectsMalformedExpression() {
        assertThatThrownBy(() -> engine.validateExpression("transaction.patternSeverity >= 2 &&"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid risk rule");
    }

    @Test
    void filtersByEffectiveWindow() {
        RiskRule rule = rule("R", "sanction.sanctionHit == true", "高风险", "AUTO_DONE", 100);
        rule.setEffectiveFrom(LocalDateTime.now().plusDays(1));
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(rule));

        assertThat(engine.evaluate(context(0, true, 0, 0, true, false, 0, 0))).isEmpty();
    }

    @Test
    void noEnabledRulesProducesNoDecision() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of());

        assertThat(engine.evaluate(context(1, true, 100, 100, false, false, 2, 2))).isEmpty();
    }

    private RiskContext context(int maxSeverity, boolean sanctionHit, double crossRatio, double nightRatio,
                                boolean dataComplete, boolean riskExplained, int patternSeverity,
                                int uboRiskSeverity) {
        return new RiskContext(maxSeverity, sanctionHit, crossRatio, nightRatio, 0,
                dataComplete, riskExplained, patternSeverity, uboRiskSeverity, "低风险", 1);
    }

    private RiskRule rule(String code, String expression, String target, String action, int priority) {
        RiskRule rule = new RiskRule();
        rule.setRuleCode(code);
        rule.setConditionExpression(expression);
        rule.setTargetRiskLevel(target);
        rule.setAction(action);
        rule.setPriority(priority);
        rule.setEnabled(true);
        return rule;
    }
}
