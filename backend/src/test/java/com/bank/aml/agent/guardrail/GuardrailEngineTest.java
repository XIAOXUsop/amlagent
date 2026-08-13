package com.bank.aml.agent.guardrail;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.datasource.mock.MockDataSource;
import com.bank.aml.risk.RiskContext;
import com.bank.aml.risk.RiskFactAssembler;
import com.bank.aml.risk.RiskRule;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuardrailEngineTest {

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
    void level1SanctionOnlyTriggersLevel1RuleAndRequiresManualReview() {
        var result = engine.apply(context(1, true, 0, 0, true, false, 0, 0), report("低风险"));

        assertThat(result.finalRiskLevel()).isEqualTo("高风险");
        assertThat(result.mustEscalate()).isTrue();
        assertThat(result.decision().triggeredRules())
                .extracting(RiskRuleEngine.TriggeredRule::ruleCode)
                .containsExactly("SANCTION_LEVEL_1");
        assertThat(result.decision().requiredAction()).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void legitimateCrossBorderActivityKeepsLowRisk() {
        var result = engine.apply(context(0, false, 65, 5, true, true, 0, 0), report("低风险"));

        assertThat(result.finalRiskLevel()).isEqualTo("低风险");
        assertThat(result.mustEscalate()).isFalse();
        assertThat(result.decision().triggeredRules()).isEmpty();
    }

    @Test
    void legitimateNightActivityAtSixtyFivePercentKeepsLowRisk() {
        var result = engine.apply(context(0, false, 0, 65, true, true, 0, 0), report("低风险"));

        assertThat(result.finalRiskLevel()).isEqualTo("低风险");
        assertThat(result.mustEscalate()).isFalse();
        assertThat(result.decision().triggeredRules()).isEmpty();
    }

    @Test
    void highSeverityTransactionPatternForcesHighRisk() {
        var result = engine.apply(context(0, false, 0, 0, true, false, 2, 0), report("低风险"));

        assertThat(result.finalRiskLevel()).isEqualTo("高风险");
        assertThat(result.mustEscalate()).isFalse();
        assertThat(result.decision().triggeredRules())
                .extracting(RiskRuleEngine.TriggeredRule::ruleCode)
                .containsExactly("TXN_PATTERN_HIGH");
    }

    @Test
    void unverifiedUboForcesHighRiskAndManualReview() {
        var result = engine.apply(context(0, false, 0, 0, true, false, 0, 2), report("低风险"));

        assertThat(result.finalRiskLevel()).isEqualTo("高风险");
        assertThat(result.mustEscalate()).isTrue();
        assertThat(result.decision().triggeredRules())
                .extracting(RiskRuleEngine.TriggeredRule::ruleCode)
                .containsExactly("UBO_UNVERIFIED");
        assertThat(result.decision().requiredAction()).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void incompleteTransactionDataForcesMediumRiskAndManualReview() {
        var result = engine.apply(context(0, false, 0, 0, false, false, 0, 0), report("低风险"));

        assertThat(result.finalRiskLevel()).isEqualTo("中风险");
        assertThat(result.mustEscalate()).isTrue();
        assertThat(result.decision().triggeredRules())
                .extracting(RiskRuleEngine.TriggeredRule::ruleCode)
                .containsExactly("DATA_INCOMPLETE");
        assertThat(result.decision().requiredAction()).isEqualTo("MANUAL_REVIEW");
    }

    @Test
    void evaluationContextCanRunWithoutAccessingMockDataSource() {
        MockDataSource unusedDataSource = mock(MockDataSource.class);
        RiskRuleRepository repository = mock(RiskRuleRepository.class);
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(seedRules());
        GuardrailEngine evaluationEngine = new GuardrailEngine(new RiskFactAssembler(unusedDataSource), new RiskRuleEngine(repository));

        RiskContext fixtureContext = context(0, false, 0, 0, true, false, 2, 0);
        var result = evaluationEngine.apply(fixtureContext, report("低风险"));

        assertThat(result.finalRiskLevel()).isEqualTo("高风险");
        assertThat(result.sanctionHits()).isEmpty();
        assertThat(result.decision().modelRiskLevel()).isEqualTo("低风险");
        verifyNoInteractions(unusedDataSource);
    }

    @Test
    void modelRequestedManualReviewIsNeverCancelledByGuardrails() {
        var result = engine.apply(context(0, false, 0, 0, true, false, 0, 0), report("低风险", true));

        assertThat(result.finalRiskLevel()).isEqualTo("低风险");
        assertThat(result.mustEscalate()).isTrue();
        assertThat(result.decision().requiredAction()).isEqualTo("MANUAL_REVIEW");
        assertThat(result.decision().triggeredRules()).isEmpty();
    }

    private RiskContext context(int maxSeverity, boolean sanctionHit, double crossRatio, double nightRatio,
                                boolean dataComplete, boolean riskExplained, int patternSeverity,
                                int uboRiskSeverity) {
        return new RiskContext(maxSeverity, sanctionHit, crossRatio, nightRatio, 0,
                dataComplete, riskExplained, patternSeverity, uboRiskSeverity, "占位值", 0);
    }

    private DueDiligenceReport report(String riskLevel) {
        return report(riskLevel, false);
    }

    private DueDiligenceReport report(String riskLevel, boolean manualReviewRequired) {
        return new DueDiligenceReport("C001", "张伟", riskLevel, "", "",
                List.of(), List.of(), List.of(riskLevel), "结论", List.of(), manualReviewRequired,
                List.of(), List.of());
    }

    private List<RiskRule> seedRules() {
        return List.of(
                rule("SANCTION_LEVEL_1", "sanction.maxSeverity == 1", "高风险", "MANUAL_REVIEW", 10),
                rule("SANCTION_OTHER", "sanction.maxSeverity >= 2", "高风险", "AUTO_DONE", 20),
                rule("DATA_INCOMPLETE", "transaction.dataComplete == false", "中风险", "MANUAL_REVIEW", 30),
                rule("UBO_UNVERIFIED", "corporate.uboRiskSeverity >= 2", "高风险", "MANUAL_REVIEW", 40),
                rule("TXN_PATTERN_HIGH", "transaction.patternSeverity >= 2", "高风险", "AUTO_DONE", 50),
                rule("TXN_ABNORMAL",
                        "transaction.crossRatio > 20 && transaction.nightRatio > 30 && transaction.riskExplained == false",
                        "高风险", "AUTO_DONE", 60),
                rule("UBO_DOCUMENT_INCOMPLETE", "corporate.uboRiskSeverity == 1", "中风险", "AUTO_DONE", 70),
                rule("TXN_MODERATE", "transaction.patternSeverity == 1", "中风险", "AUTO_DONE", 80),
                rule("CROSS_BORDER_MODERATE",
                        "transaction.crossRatio >= 10 && transaction.riskExplained == false",
                        "中风险", "AUTO_DONE", 90),
                rule("NIGHT_ACTIVITY_MODERATE",
                        "transaction.nightRatio >= 20 && transaction.riskExplained == false",
                        "中风险", "AUTO_DONE", 100));
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
