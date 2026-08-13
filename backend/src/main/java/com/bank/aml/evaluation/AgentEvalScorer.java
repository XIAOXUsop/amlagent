package com.bank.aml.evaluation;

import com.bank.aml.evaluation.AgentEvalReport.BinaryMetrics;
import com.bank.aml.evaluation.AgentEvalReport.CaseResult;
import com.bank.aml.evaluation.AgentEvalReport.CodeMetrics;
import com.bank.aml.evaluation.AgentEvalReport.CitationMetrics;
import com.bank.aml.evaluation.AgentEvalReport.ForbiddenMetrics;
import com.bank.aml.evaluation.AgentEvalReport.GuardrailMetrics;
import com.bank.aml.evaluation.AgentEvalReport.LatencyMetrics;
import com.bank.aml.evaluation.AgentEvalReport.Rate;
import com.bank.aml.evaluation.AgentEvalReport.RiskMetrics;
import com.bank.aml.evaluation.AgentEvalReport.SchemaMetrics;
import com.bank.aml.evaluation.AgentEvalReport.TokenMetrics;
import com.bank.aml.evaluation.AgentEvalReport.ToolMetrics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Aggregates deterministic metrics; it never asks a second model to judge the first model. */
@Component
public class AgentEvalScorer {

    private static final List<String> RISK_LEVELS = List.of("低风险", "中风险", "高风险");

    public Aggregate aggregate(List<CaseResult> cases) {
        List<CaseResult> scored = cases.stream().filter(c -> "SCORED".equals(c.status())).toList();
        long strictPasses = scored.stream().filter(CaseResult::strictPass).count();
        long taskPasses = scored.stream().filter(CaseResult::endToEndTaskPass).count();
        return new Aggregate(
                strictPasses,
                rate(strictPasses, cases.size()),
                taskPasses,
                rate(taskPasses, cases.size()),
                schemaMetrics(cases),
                riskMetrics(cases, CaseResult::actualRawRisk, CaseResult::actualRawEscalation),
                riskMetrics(cases, CaseResult::finalRisk, c -> c.finalEscalation()),
                guardrailMetrics(scored),
                binaryMetrics(cases, CaseResult::actualRawEscalation),
                binaryMetrics(cases, c -> c.finalEscalation()),
                codeMetrics(cases, CaseResult::requiredFindings, CaseResult::missingFindings,
                        CaseResult::unsupportedFindings),
                codeMetrics(cases, CaseResult::requiredActions, CaseResult::missingActions,
                        CaseResult::unsupportedActions),
                citationMetrics(cases),
                toolMetrics(cases),
                forbiddenMetrics(cases),
                latencyMetrics(cases),
                tokenMetrics(cases)
        );
    }

    private SchemaMetrics schemaMetrics(List<CaseResult> cases) {
        long successes = cases.stream().filter(c -> "SCORED".equals(c.status())).count();
        Map<String, Long> counts = cases.stream()
                .flatMap(c -> c.schemaViolations().stream())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return new SchemaMetrics(rate(successes, cases.size()), counts);
    }

    private RiskMetrics riskMetrics(List<CaseResult> cases, Function<CaseResult, String> prediction,
                                    Function<CaseResult, Boolean> escalation) {
        Map<String, Map<String, Long>> confusion = emptyConfusion();
        long correct = 0;
        long highExpected = 0;
        long highHit = 0;
        long under = 0;
        long criticalMiss = 0;
        double absoluteError = 0;

        for (CaseResult evalCase : cases) {
            String expected = evalCase.expectedRawRisk();
            String actual = prediction.apply(evalCase);
            Boolean actualEscalation = escalation.apply(evalCase);
            boolean validPrediction = "SCORED".equals(evalCase.status()) && RISK_LEVELS.contains(actual);
            if (!validPrediction) {
                if ("高风险".equals(expected)) {
                    highExpected++;
                    under++;
                }
                if (isCriticalMiss(evalCase, actual, actualEscalation)) {
                    criticalMiss++;
                }
                absoluteError += 2;
                continue;
            }
            confusion.get(expected).merge(actual, 1L, Long::sum);
            // 精确准确：风险评级正确且无 critical miss（该转人工未转人工不算正确）
            if (expected.equals(actual) && !isCriticalMiss(evalCase, actual, actualEscalation)) {
                correct++;
            }
            if ("高风险".equals(expected)) {
                highExpected++;
                if ("高风险".equals(actual)) {
                    highHit++;
                }
            }
            int expectedCode = riskCode(expected);
            int actualCode = riskCode(actual);
            absoluteError += Math.abs(expectedCode - actualCode);
            if (actualCode < expectedCode) {
                under++;
            }
            if (isCriticalMiss(evalCase, actual, actualEscalation)) {
                criticalMiss++;
            }
        }

        List<Double> recalls = new ArrayList<>();
        List<Double> f1Values = new ArrayList<>();
        for (String level : RISK_LEVELS) {
            long tp = confusion.get(level).get(level);
            long expectedTotal = cases.stream().filter(c -> level.equals(c.expectedRawRisk())).count();
            long predictedTotal = confusion.values().stream().mapToLong(row -> row.get(level)).sum();
            double recall = expectedTotal == 0 ? 0 : (double) tp / expectedTotal;
            double precision = predictedTotal == 0 ? 0 : (double) tp / predictedTotal;
            recalls.add(recall);
            f1Values.add(precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall));
        }
        return new RiskMetrics(
                rate(correct, cases.size()),
                rate(highHit, highExpected),
                round(f1Values.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100),
                round(recalls.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 100),
                cases.isEmpty() ? null : round(absoluteError / cases.size()),
                under,
                criticalMiss,
                confusion
        );
    }

    private BinaryMetrics binaryMetrics(List<CaseResult> cases, Function<CaseResult, Boolean> prediction) {
        long correct = 0, truePositive = 0, predictedPositive = 0, actualPositive = 0, falseNegative = 0;
        for (CaseResult evalCase : cases) {
            boolean expected = evalCase.expectedEscalation();
            Boolean predicted = prediction.apply(evalCase);
            boolean validPrediction = "SCORED".equals(evalCase.status()) && predicted != null;
            boolean actual = Boolean.TRUE.equals(predicted);
            if (validPrediction && expected == actual) correct++;
            if (validPrediction && actual) predictedPositive++;
            if (expected) actualPositive++;
            if (validPrediction && expected && actual) truePositive++;
            if (expected && (!validPrediction || !actual)) falseNegative++;
        }
        return new BinaryMetrics(rate(correct, cases.size()), rate(truePositive, predictedPositive),
                rate(truePositive, actualPositive), falseNegative);
    }

    private CodeMetrics codeMetrics(List<CaseResult> cases,
                                    Function<CaseResult, List<String>> required,
                                    Function<CaseResult, List<String>> missing,
                                    Function<CaseResult, List<String>> unsupported) {
        long requiredTotal = cases.stream().map(required).mapToLong(List::size).sum();
        long missingTotal = cases.stream().map(missing).mapToLong(List::size).sum();
        long unsupportedTotal = cases.stream().map(unsupported).mapToLong(List::size).sum();
        long emittedTotal = requiredTotal - missingTotal + unsupportedTotal;
        long fullCoverage = cases.stream().filter(c -> missing.apply(c).isEmpty()).count();
        return new CodeMetrics(
                rate(requiredTotal - missingTotal, requiredTotal),
                rate(emittedTotal - unsupportedTotal, emittedTotal),
                rate(fullCoverage, cases.size()),
                unsupportedTotal
        );
    }

    private GuardrailMetrics guardrailMetrics(List<CaseResult> cases) {
        long upgrades = cases.stream().filter(c -> riskCode(c.finalRisk()) > riskCode(c.actualRawRisk())).count();
        long falseUpgrades = cases.stream().filter(c -> riskCode(c.finalRisk()) > riskCode(c.actualRawRisk())
                && !c.expectedRawRisk().equals(c.finalRisk())).count();
        long preventedCritical = cases.stream().filter(c ->
                isCriticalMiss(c, c.actualRawRisk(), c.actualRawEscalation())
                        && !isCriticalMiss(c, c.finalRisk(), c.finalEscalation())).count();
        Map<String, Long> rules = cases.stream().flatMap(c -> c.triggeredGuardrailRules().stream())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return new GuardrailMetrics(upgrades, falseUpgrades, preventedCritical, rules);
    }

    private ToolMetrics toolMetrics(List<CaseResult> cases) {
        long required = 0, matched = 0, validCalls = 0, allCalls = 0, exactCases = 0;
        long invalidArgs = 0, duplicates = 0, failures = 0;
        for (CaseResult evalCase : cases) {
            required += 4;
            matched += 4L - evalCase.missingTools().size();
            allCalls += evalCase.toolCalls().size();
            validCalls += evalCase.toolCalls().stream().filter(t -> t.success() && t.argumentValid()).count();
            invalidArgs += evalCase.invalidArgumentCalls();
            duplicates += evalCase.duplicateCalls();
            failures += evalCase.toolCalls().stream().filter(t -> !t.success() && t.argumentValid()).count();
            if (evalCase.missingTools().isEmpty() && evalCase.invalidArgumentCalls() == 0
                    && evalCase.duplicateCalls() == 0) {
                exactCases++;
            }
        }
        long precisionDenominator = matched + invalidArgs + duplicates + failures;
        return new ToolMetrics(rate(matched, required), rate(matched, precisionDenominator),
                rate(validCalls, allCalls), rate(exactCases, cases.size()), invalidArgs, duplicates, failures,
                cases.isEmpty() ? 0 : round((double) allCalls / cases.size()));
    }

    private CitationMetrics citationMetrics(List<CaseResult> cases) {
        long required = cases.stream().mapToLong(c -> c.requiredEvidenceIds().size()).sum();
        long missing = cases.stream().mapToLong(c -> c.missingEvidenceIds().size()).sum();
        long full = cases.stream().filter(c -> c.missingEvidenceIds().isEmpty()
                && "SCORED".equals(c.status())).count();
        return new CitationMetrics(rate(required - missing, required), rate(full, cases.size()));
    }

    private ForbiddenMetrics forbiddenMetrics(List<CaseResult> cases) {
        long pass = cases.stream().flatMap(c -> c.forbiddenChecks().stream())
                .filter(check -> "PASS".equals(check.status())).count();
        long violations = cases.stream().flatMap(c -> c.forbiddenChecks().stream())
                .filter(check -> "VIOLATION".equals(check.status())).count();
        long unscorable = cases.stream().flatMap(c -> c.forbiddenChecks().stream())
                .filter(check -> "UNSCORABLE".equals(check.status())).count();
        long expectedChecks = cases.stream().mapToLong(c -> c.expectedForbiddenClaims().size()).sum();
        return new ForbiddenMetrics(rate(pass, pass + violations),
                rate(pass + violations, expectedChecks), violations,
                Math.max(unscorable, expectedChecks - pass - violations));
    }

    private LatencyMetrics latencyMetrics(List<CaseResult> cases) {
        long[] values = cases.stream().mapToLong(CaseResult::durationMs).sorted().toArray();
        return new LatencyMetrics(percentile(values, .50), percentile(values, .95));
    }

    private TokenMetrics tokenMetrics(List<CaseResult> cases) {
        return new TokenMetrics(
                cases.stream().map(CaseResult::model).filter(java.util.Objects::nonNull)
                        .mapToLong(AgentEvalModelObserver.Snapshot::inputTokens).sum(),
                cases.stream().map(CaseResult::model).filter(java.util.Objects::nonNull)
                        .mapToLong(AgentEvalModelObserver.Snapshot::outputTokens).sum(),
                cases.stream().map(CaseResult::model).filter(java.util.Objects::nonNull)
                        .mapToLong(AgentEvalModelObserver.Snapshot::totalTokens).sum(),
                cases.stream().map(CaseResult::model).filter(java.util.Objects::nonNull)
                        .mapToInt(AgentEvalModelObserver.Snapshot::requestCount).sum()
        );
    }

    static Rate rate(long numerator, long denominator) {
        return new Rate(numerator, denominator,
                denominator == 0 ? null : round(100.0 * numerator / denominator));
    }

    private Map<String, Map<String, Long>> emptyConfusion() {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();
        for (String expected : RISK_LEVELS) {
            Map<String, Long> row = new LinkedHashMap<>();
            RISK_LEVELS.forEach(actual -> row.put(actual, 0L));
            result.put(expected, row);
        }
        return result;
    }

    private int riskCode(String risk) {
        return switch (risk) {
            case "高风险" -> 2;
            case "中风险" -> 1;
            case "低风险" -> 0;
            default -> -1;
        };
    }

    private boolean isCriticalMiss(CaseResult evalCase, String actualRisk, Boolean actualEscalation) {
        boolean invalidPrediction = !"SCORED".equals(evalCase.status()) || !RISK_LEVELS.contains(actualRisk);
        boolean highRiskMiss = "高风险".equals(evalCase.expectedRawRisk())
                && (invalidPrediction || !"高风险".equals(actualRisk));
        boolean escalationMiss = evalCase.expectedEscalation()
                && (invalidPrediction || !Boolean.TRUE.equals(actualEscalation));
        return highRiskMiss || escalationMiss;
    }

    private long percentile(long[] values, double percentile) {
        if (values.length == 0) return 0;
        int index = Math.max(0, (int) Math.ceil(percentile * values.length) - 1);
        return values[index];
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public record Aggregate(
            long strictPassCount,
            Rate strictPassRate,
            long taskPassCount,
            Rate taskPassRate,
            SchemaMetrics schema,
            RiskMetrics rawRisk,
            RiskMetrics finalRisk,
            GuardrailMetrics guardrails,
            BinaryMetrics rawEscalation,
            BinaryMetrics finalEscalation,
            CodeMetrics findings,
            CodeMetrics actions,
            CitationMetrics citations,
            ToolMetrics tools,
            ForbiddenMetrics forbiddenClaims,
            LatencyMetrics latency,
            TokenMetrics tokens
    ) {
    }
}
