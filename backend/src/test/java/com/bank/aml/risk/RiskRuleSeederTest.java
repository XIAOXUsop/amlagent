package com.bank.aml.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskRuleSeederTest {

    private RiskRuleRepository repository;
    private RiskRuleSeeder seeder;

    @BeforeEach
    void setUp() {
        repository = mock(RiskRuleRepository.class);
        seeder = new RiskRuleSeeder(repository, new RiskRuleEngine(repository));
    }

    @Test
    void createsCompleteManagedRuleSetInEmptyRepository() {
        when(repository.findByRuleCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of());

        seeder.run(null);

        ArgumentCaptor<RiskRule> captor = ArgumentCaptor.forClass(RiskRule.class);
        verify(repository, times(10)).save(captor.capture());
        Map<String, RiskRule> rules = byCode(captor.getAllValues());

        assertThat(rules).hasSize(10);
        assertRule(rules, "SANCTION_LEVEL_1", 2, "sanction.maxSeverity == 1", "高风险", "MANUAL_REVIEW");
        assertRule(rules, "SANCTION_OTHER", 2, "sanction.maxSeverity >= 2", "高风险", "AUTO_DONE");
        assertRule(rules, "DATA_INCOMPLETE", 1, "transaction.dataComplete == false", "中风险", "MANUAL_REVIEW");
        assertRule(rules, "UBO_UNVERIFIED", 1, "corporate.uboRiskSeverity >= 2", "高风险", "MANUAL_REVIEW");
        assertRule(rules, "TXN_PATTERN_HIGH", 1, "transaction.patternSeverity >= 2", "高风险", "AUTO_DONE");
        assertRule(rules, "TXN_ABNORMAL", 2,
                "transaction.crossRatio > 20 && transaction.nightRatio > 30 && transaction.riskExplained == false",
                "高风险", "AUTO_DONE");
        assertRule(rules, "UBO_DOCUMENT_INCOMPLETE", 1, "corporate.uboRiskSeverity == 1", "中风险", "AUTO_DONE");
        assertRule(rules, "TXN_MODERATE", 2, "transaction.patternSeverity == 1", "中风险", "AUTO_DONE");
        assertRule(rules, "CROSS_BORDER_MODERATE", 1,
                "transaction.crossRatio >= 10 && transaction.riskExplained == false", "中风险", "AUTO_DONE");
        assertRule(rules, "NIGHT_ACTIVITY_MODERATE", 1,
                "transaction.nightRatio >= 20 && transaction.riskExplained == false", "中风险", "AUTO_DONE");
    }

    @Test
    void upgradesVersionOneManagedRuleToVersionTwoInPlace() {
        RiskRule legacy = rule("TXN_ABNORMAL", 1,
                "transaction.crossRatio > 20 && transaction.nightRatio > 30", "高风险", "AUTO_DONE");
        when(repository.findByRuleCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByRuleCode("TXN_ABNORMAL")).thenReturn(Optional.of(legacy));
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(legacy));

        seeder.run(null);

        verify(repository).save(same(legacy));
        assertThat(legacy.getVersion()).isEqualTo(2);
        assertThat(legacy.getConditionExpression()).isEqualTo(
                "transaction.crossRatio > 20 && transaction.nightRatio > 30 && transaction.riskExplained == false");
        assertThat(legacy.getPriority()).isEqualTo(60);
    }

    @Test
    void doesNotOverwriteHumanEditedRuleAtCurrentVersion() {
        RiskRule customized = rule("TXN_MODERATE", 2,
                "transaction.largeCount >= 3", "高风险", "MANUAL_REVIEW");
        customized.setDescription("合规团队人工配置");
        when(repository.findByRuleCode(anyString())).thenReturn(Optional.empty());
        when(repository.findByRuleCode("TXN_MODERATE")).thenReturn(Optional.of(customized));
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenReturn(List.of(customized));

        seeder.run(null);

        verify(repository, never()).save(same(customized));
        assertThat(customized.getConditionExpression()).isEqualTo("transaction.largeCount >= 3");
        assertThat(customized.getTargetRiskLevel()).isEqualTo("高风险");
        assertThat(customized.getAction()).isEqualTo("MANUAL_REVIEW");
        assertThat(customized.getDescription()).isEqualTo("合规团队人工配置");
    }

    @Test
    void runningSeederTwiceIsIdempotentEvenWhenNewEntitiesHaveNoId() {
        Map<String, RiskRule> database = new HashMap<>();
        when(repository.findByRuleCode(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(database.get(invocation.getArgument(0))));
        when(repository.save(any(RiskRule.class))).thenAnswer(invocation -> {
            RiskRule saved = invocation.getArgument(0);
            database.put(saved.getRuleCode(), saved);
            return saved;
        });
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenAnswer(invocation -> database.values().stream()
                .filter(RiskRule::isEnabled)
                .sorted(Comparator.comparingInt(RiskRule::getPriority))
                .toList());

        seeder.run(null);
        seeder.run(null);

        assertThat(database).hasSize(10);
        assertThat(database.values()).allMatch(rule -> rule.getId() == null);
        verify(repository, times(10)).save(any(RiskRule.class));
        verify(repository, atLeastOnce()).findByEnabledTrueOrderByPriorityAsc();
    }

    private Map<String, RiskRule> byCode(List<RiskRule> rules) {
        Map<String, RiskRule> result = new HashMap<>();
        for (RiskRule rule : new ArrayList<>(rules)) {
            result.put(rule.getRuleCode(), rule);
        }
        return result;
    }

    private void assertRule(Map<String, RiskRule> rules, String code, int version, String expression,
                            String target, String action) {
        assertThat(rules).containsKey(code);
        RiskRule rule = rules.get(code);
        assertThat(rule.getVersion()).isEqualTo(version);
        assertThat(rule.getConditionExpression()).isEqualTo(expression);
        assertThat(rule.getTargetRiskLevel()).isEqualTo(target);
        assertThat(rule.getAction()).isEqualTo(action);
        assertThat(rule.isEnabled()).isTrue();
    }

    private RiskRule rule(String code, int version, String expression, String target, String action) {
        RiskRule rule = new RiskRule();
        rule.setRuleCode(code);
        rule.setVersion(version);
        rule.setRuleName("人工规则");
        rule.setPriority(999);
        rule.setConditionExpression(expression);
        rule.setTargetRiskLevel(target);
        rule.setAction(action);
        rule.setDescription("旧描述");
        rule.setEnabled(true);
        return rule;
    }
}
