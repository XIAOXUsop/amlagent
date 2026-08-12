package com.bank.aml.agent.guardrail;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.datasource.mock.MockDataSource;
import com.bank.aml.risk.RiskRule;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardrailEngineTest {

    private MockDataSource dataSource;
    private GuardrailEngine engine;

    @BeforeEach
    void setUp() {
        dataSource = new MockDataSource();
        dataSource.init();
        RiskRuleRepository repo = mock(RiskRuleRepository.class);
        when(repo.findByEnabledTrueOrderByPriorityAsc()).thenReturn(seedRules());
        engine = new GuardrailEngine(dataSource, new RiskRuleEngine(repo));
    }

    @Test
    void level1SanctionForcesHighRiskAndEscalation() {
        MockDataSource.Customer c = dataSource.findCustomer("C001").orElseThrow();
        DueDiligenceReport report = report("低风险");
        var result = engine.apply(c, report);
        assertThat(result.finalRiskLevel()).isEqualTo("高风险");
        assertThat(result.mustEscalate()).isTrue();
        assertThat(result.decision().triggeredRules())
                .extracting(r -> r.ruleCode()).contains("SANCTION_LEVEL_1");
        assertThat(result.decision().requiredAction()).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void abnormalTransactionsUpgradeRating() {
        MockDataSource.Customer c = dataSource.findCustomer("C001").orElseThrow();
        DueDiligenceReport report = report("低风险");
        // 构造交易特征（C001 本身即跨境+夜间高频）
        var result = engine.apply(c, report);
        assertThat(result.finalRiskLevel()).isEqualTo("高风险");
    }

    @Test
    void normalCustomerKeepsRating() {
        MockDataSource.Customer c = dataSource.findCustomer("C003").orElseThrow();
        DueDiligenceReport report = report("中风险");
        var result = engine.apply(c, report);
        // C003 夜间占比 >0 → TXN_MODERATE 触发 → 至少中风险
        assertThat(result.finalRiskLevel()).isEqualTo("中风险");
        assertThat(result.mustEscalate()).isFalse();
    }

    private DueDiligenceReport report(String riskLevel) {
        return new DueDiligenceReport("C001", "张伟", riskLevel, "", "",
                List.of(), List.of(), List.of(riskLevel), "结论", List.of());
    }

    private List<RiskRule> seedRules() {
        return List.of(
                rule("SANCTION_LEVEL_1", "sanction.maxSeverity == 1", "高风险", "MANUAL_REVIEW", 100),
                rule("SANCTION_OTHER", "sanction.sanctionHit == true", "高风险", "AUTO_DONE", 90),
                rule("TXN_ABNORMAL", "transaction.crossRatio > 20 && transaction.nightRatio > 30",
                        "高风险", "AUTO_DONE", 80),
                rule("TXN_MODERATE", "transaction.crossRatio > 0 || transaction.nightRatio > 0",
                        "中风险", "AUTO_DONE", 70));
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
