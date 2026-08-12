package com.bank.aml.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    void evaluatesSanctionSeverityRule() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(
                rule("SANCTION_LEVEL_1", "sanction.maxSeverity == 1", "高风险", "MANUAL_REVIEW", 100)));
        RiskContext ctx = new RiskContext(1, true, 0, 0, 0, "中风险", 2);
        var triggered = engine.evaluate(ctx);
        assertThat(triggered).hasSize(1);
        assertThat(triggered.get(0).ruleCode()).isEqualTo("SANCTION_LEVEL_1");
        assertThat(triggered.get(0).action()).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void evaluatesAndExpression() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(
                rule("TXN_ABNORMAL", "transaction.crossRatio > 20 && transaction.nightRatio > 30",
                        "高风险", "AUTO_DONE", 80)));
        assertThat(engine.evaluate(new RiskContext(0, false, 25, 40, 0, "中风险", 2))).hasSize(1);
        // 跨境占比不满足阈值 → 不触发
        assertThat(engine.evaluate(new RiskContext(0, false, 10, 40, 0, "中风险", 2))).isEmpty();
    }

    @Test
    void evaluatesOrExpression() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(
                rule("TXN_MODERATE", "transaction.crossRatio > 0 || transaction.nightRatio > 0",
                        "中风险", "AUTO_DONE", 70)));
        assertThat(engine.evaluate(new RiskContext(0, false, 5, 0, 0, "中风险", 2))).hasSize(1);
        assertThat(engine.evaluate(new RiskContext(0, false, 0, 0, 0, "中风险", 2))).isEmpty();
    }

    @Test
    void filtersByEffectiveWindow() {
        RiskRule r = rule("R", "sanction.sanctionHit == true", "高风险", "AUTO_DONE", 100);
        r.setEffectiveFrom(LocalDateTime.now().plusDays(1));
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(r));
        // 生效窗口未到 → 不触发
        assertThat(engine.evaluate(new RiskContext(0, true, 0, 0, 0, "中风险", 2))).isEmpty();
    }

    @Test
    void disabledRulesAreExcluded() {
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of());
        assertThat(engine.evaluate(new RiskContext(1, true, 0, 0, 0, "中风险", 2))).isEmpty();
    }

    private RiskRule rule(String code, String expr, String target, String action, int priority) {
        RiskRule r = new RiskRule();
        r.setRuleCode(code);
        r.setConditionExpression(expr);
        r.setTargetRiskLevel(target);
        r.setAction(action);
        r.setPriority(priority);
        r.setEnabled(true);
        return r;
    }
}
