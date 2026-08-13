package com.bank.aml.agent.guardrail;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.datasource.mock.MockDataSource;
import com.bank.aml.risk.RiskFactAssembler;
import com.bank.aml.risk.RiskRule;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证生产链路 GuardrailEngine 从 Mock 数据正确计算新增风险事实
 * （dataComplete / patternSeverity / uboRiskSeverity），使新增规则真实可触发。
 */
class GuardrailEngineRiskFactTest {

    private MockDataSource dataSource;
    private GuardrailEngine engine;

    @BeforeEach
    void setUp() {
        dataSource = new MockDataSource();
        dataSource.init();
        RiskRuleRepository repository = mock(RiskRuleRepository.class);
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(seedRules());
        engine = new GuardrailEngine(new RiskFactAssembler(dataSource), new RiskRuleEngine(repository));
    }

    @Test
    void c001ComplexUboTriggersUboUnverifiedRule() {
        var customer = dataSource.findCustomer("C001").orElseThrow();
        var result = engine.apply(customer, report("低风险"));
        assertThat(result.decision().triggeredRules())
                .extracting(r -> r.ruleCode())
                .contains("UBO_UNVERIFIED", "TXN_PATTERN_HIGH");
        assertThat(result.mustEscalate()).isTrue();
    }

    @Test
    void c002StructuringTriggersPatternRuleAndUboDocumentIncomplete() {
        var customer = dataSource.findCustomer("C002").orElseThrow();
        var result = engine.apply(customer, report("低风险"));
        assertThat(result.decision().triggeredRules())
                .extracting(r -> r.ruleCode())
                .contains("TXN_PATTERN_HIGH", "UBO_DOCUMENT_INCOMPLETE");
    }

    @Test
    void c003NormalCustomerDoesNotTriggerPatternOrUboRules() {
        var customer = dataSource.findCustomer("C003").orElseThrow();
        var result = engine.apply(customer, report("低风险"));
        assertThat(result.decision().triggeredRules())
                .extracting(r -> r.ruleCode())
                .doesNotContain("TXN_PATTERN_HIGH", "UBO_UNVERIFIED", "UBO_DOCUMENT_INCOMPLETE");
    }

    private DueDiligenceReport report(String riskLevel) {
        return new DueDiligenceReport("C001", "张伟", riskLevel, "", "",
                List.of(), List.of(), List.of(riskLevel), "结论", List.of(), false,
                List.of(), List.of());
    }

    private List<RiskRule> seedRules() {
        return List.of(
                rule("SANCTION_LEVEL_1", "sanction.maxSeverity == 1", "高风险", "MANUAL_REVIEW", 10),
                rule("SANCTION_OTHER", "sanction.maxSeverity >= 2", "高风险", "AUTO_DONE", 20),
                rule("DATA_INCOMPLETE", "transaction.dataComplete == false", "中风险", "MANUAL_REVIEW", 30),
                rule("UBO_UNVERIFIED", "corporate.uboRiskSeverity >= 2", "高风险", "MANUAL_REVIEW", 40),
                rule("TXN_PATTERN_HIGH", "transaction.patternSeverity >= 2", "高风险", "AUTO_DONE", 50),
                rule("UBO_DOCUMENT_INCOMPLETE", "corporate.uboRiskSeverity == 1", "中风险", "AUTO_DONE", 70),
                rule("TXN_MODERATE", "transaction.patternSeverity == 1", "中风险", "AUTO_DONE", 80));
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
