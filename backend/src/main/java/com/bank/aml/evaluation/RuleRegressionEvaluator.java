package com.bank.aml.evaluation;

import com.bank.aml.evaluation.RuleRegressionCaseGenerator.RuleRegressionCase;
import com.bank.aml.risk.RiskContext;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleEngine.TriggeredRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 风险规则回归评测。
 *
 * <p>本评测不调用真实 LLM，也不执行 Agent 工具，只验证 RiskRuleEngine
 * 和 Guardrails 在确定性合成数据上的行为是否符合预期。
 */
@Service
public class RuleRegressionEvaluator {

    private final RuleRegressionCaseGenerator generator;
    private final RiskRuleEngine ruleEngine;
    private final ObjectMapper objectMapper;

    public RuleRegressionEvaluator(RuleRegressionCaseGenerator generator, RiskRuleEngine ruleEngine,
                                   ObjectMapper objectMapper) {
        this.generator = generator;
        this.ruleEngine = ruleEngine;
        this.objectMapper = objectMapper;
    }

    public record PerCase(String id, String scenario, String expectedRiskLevel,
                          String baselineRiskLevel, String finalRiskLevel, boolean escalated) {
    }

    public record RuleRegressionReport(
            int totalCases,
            double highRiskRecallRate,
            double lowRiskFalsePositiveRate,
            double accuracy,
            int manualReviewMissCount,
            int manualReviewTotal,
            long p50DurationMs,
            long p95DurationMs,
            int[][] confusionMatrix,
            List<PerCase> details
    ) {
    }

    public RuleRegressionReport run() {
        List<RuleRegressionCase> cases = generator.generate();
        int[][] confusion = new int[3][3];
        int highRecallHit = 0, highActual = 0, lowFalsePositive = 0, lowActual = 0;
        int manualReviewMiss = 0, manualReviewTotal = 0;
        int correct = 0;
        List<Long> durations = new ArrayList<>();
        List<PerCase> details = new ArrayList<>();

        for (RuleRegressionCase regressionCase : cases) {
            long start = System.currentTimeMillis();

            String baselineLevel = baselineLevel(regressionCase);
            RiskContext context = new RiskContext(regressionCase.maxSeverity(), regressionCase.sanctionHit(),
                    regressionCase.crossRatio(), regressionCase.nightRatio(), regressionCase.largeCount(),
                    regressionCase.transactionDataComplete(), regressionCase.transactionRiskExplained(),
                    regressionCase.transactionPatternSeverity(), regressionCase.uboRiskSeverity(),
                    baselineLevel, levelCode(baselineLevel));
            List<TriggeredRule> triggered = ruleEngine.evaluate(context);
            String finalLevel = applyRules(baselineLevel, triggered);
            boolean escalate = triggered.stream().anyMatch(r -> "MANUAL_REVIEW".equals(r.action()));
            durations.add(System.currentTimeMillis() - start);

            confusion[levelIndex(regressionCase.expectedRiskLevel())][levelIndex(finalLevel)]++;
            if (regressionCase.expectedRiskLevel().equals(finalLevel)) {
                correct++;
            }
            if ("高风险".equals(regressionCase.expectedRiskLevel())) {
                highActual++;
                if ("高风险".equals(finalLevel)) {
                    highRecallHit++;
                }
            }
            if ("低风险".equals(regressionCase.expectedRiskLevel())) {
                lowActual++;
                if (!"低风险".equals(finalLevel)) {
                    lowFalsePositive++;
                }
            }
            if (regressionCase.expectManualReview()) {
                manualReviewTotal++;
                if (!escalate) {
                    manualReviewMiss++;
                }
            }
            details.add(new PerCase(regressionCase.id(), regressionCase.scenario(),
                    regressionCase.expectedRiskLevel(), baselineLevel, finalLevel, escalate));
        }

        int total = cases.size();
        double highRecall = highActual == 0 ? 100.0 : 100.0 * highRecallHit / highActual;
        double lowFalsePositiveRate = lowActual == 0 ? 0 : 100.0 * lowFalsePositive / lowActual;
        double accuracy = total == 0 ? 0 : 100.0 * correct / total;

        return new RuleRegressionReport(total, round1(highRecall), round1(lowFalsePositiveRate), round1(accuracy),
                manualReviewMiss, manualReviewTotal, percentile(durations, 0.50), percentile(durations, 0.95),
                confusion, details);
    }

    /**
     * 固定低风险基线，用于隔离并验证护栏的升级行为。
     * 这里不复制任何待测规则，也不代表真实模型预测。
     */
    private String baselineLevel(RuleRegressionCase regressionCase) {
        return "低风险";
    }

    private String applyRules(String baselineLevel, List<TriggeredRule> rules) {
        String finalLevel = baselineLevel;
        for (TriggeredRule rule : rules) {
            if (levelCode(rule.targetRiskLevel()) > levelCode(finalLevel)) {
                finalLevel = rule.targetRiskLevel();
            }
        }
        return finalLevel;
    }

    private int levelIndex(String level) {
        return switch (level) {
            case "高风险" -> 0;
            case "中风险" -> 1;
            default -> 2;
        };
    }

    private int levelCode(String level) {
        return switch (level) {
            case "高风险" -> 3;
            case "中风险" -> 2;
            default -> 1;
        };
    }

    private long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        long[] sorted = values.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, index)];
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public String metricsJson(RuleRegressionReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            return "{}";
        }
    }
}
