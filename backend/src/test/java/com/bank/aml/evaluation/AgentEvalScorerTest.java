package com.bank.aml.evaluation;

import com.bank.aml.evaluation.AgentEvalReport.CaseResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvalScorerTest {

    private final AgentEvalScorer scorer = new AgentEvalScorer();

    @Test
    void modelErrorsStayInStrictRiskDenominator() {
        CaseResult scored = result("SCORED", "高风险", "高风险", true, true);
        CaseResult failed = result("MODEL_ERROR", "高风险", null, true, false);

        var aggregate = scorer.aggregate(List.of(scored, failed));

        assertThat(aggregate.rawRisk().exactAccuracy().numerator()).isEqualTo(1);
        assertThat(aggregate.rawRisk().exactAccuracy().denominator()).isEqualTo(2);
        assertThat(aggregate.rawRisk().highRiskRecall().value()).isEqualTo(50.0);
        assertThat(aggregate.rawRisk().criticalMissCount()).isEqualTo(1);
    }

    @Test
    void emptyDenominatorProducesNullRateInsteadOfNaN() {
        var aggregate = scorer.aggregate(List.of());

        assertThat(aggregate.rawRisk().exactAccuracy().value()).isNull();
        assertThat(aggregate.tools().requiredToolRecall().value()).isNull();
    }

    @Test
    void failedNegativeCaseIsNotCountedAsCorrectEscalationPrediction() {
        CaseResult failed = result("MODEL_ERROR", "低风险", null, false, false);

        var aggregate = scorer.aggregate(List.of(failed));

        assertThat(aggregate.rawEscalation().accuracy().numerator()).isZero();
        assertThat(aggregate.rawEscalation().accuracy().denominator()).isEqualTo(1);
        assertThat(aggregate.findings().microRecall().value()).isEqualTo(0.0);
        assertThat(aggregate.actions().microRecall().value()).isEqualTo(0.0);
    }

    @Test
    void invalidPositiveFieldDoesNotPolluteEscalationPrecisionDenominator() {
        CaseResult invalid = result("SCHEMA_INVALID", "低风险", "低风险", false, true);

        var aggregate = scorer.aggregate(List.of(invalid));

        assertThat(aggregate.rawEscalation().precision().denominator()).isZero();
        assertThat(aggregate.rawEscalation().precision().value()).isNull();
    }

    @Test
    void criticalMissIsUnionOfHighRiskAndRequiredEscalationMisses() {
        CaseResult highRiskMiss = result("SCORED", "高风险", "中风险", false, false);
        CaseResult escalationMiss = result("SCORED", "中风险", "中风险", true, false);
        CaseResult bothMisses = result("SCORED", "高风险", "中风险", true, false);
        CaseResult invalidHighText = result("SCHEMA_INVALID", "高风险", "高风险", false, false);

        var aggregate = scorer.aggregate(List.of(highRiskMiss, escalationMiss, bothMisses, invalidHighText));

        assertThat(aggregate.rawRisk().criticalMissCount()).isEqualTo(4);
        assertThat(aggregate.rawRisk().exactAccuracy().numerator()).isZero();
    }

    @Test
    void aggregatesEndToEndTaskPassSeparatelyFromStrictPass() {
        CaseResult taskPass = result("SCORED", "高风险", "高风险", true, true, true, false);
        CaseResult failed = result("MODEL_ERROR", "高风险", null, true, false, false, false);

        var aggregate = scorer.aggregate(List.of(taskPass, failed));

        assertThat(aggregate.taskPassCount()).isEqualTo(1);
        assertThat(aggregate.taskPassRate().numerator()).isEqualTo(1);
        assertThat(aggregate.taskPassRate().denominator()).isEqualTo(2);
        assertThat(aggregate.taskPassRate().value()).isEqualTo(50.0);
        assertThat(aggregate.strictPassCount()).isZero();
    }

    private CaseResult result(String status, String expected, String actual,
                              boolean expectedEscalation, boolean actualEscalation) {
        return result(status, expected, actual, expectedEscalation, actualEscalation, false, false);
    }

    private CaseResult result(String status, String expected, String actual,
                              boolean expectedEscalation, boolean actualEscalation,
                              boolean endToEndTaskPass, boolean strictPass) {
        return new CaseResult(
                "case-" + status, "scenario", status, null,
                "SCORED".equals(status) ? List.of() : List.of(status),
                expected, actual, expected.equals(actual), actual, expected.equals(actual),
                expectedEscalation, "SCORED".equals(status) ? actualEscalation : null,
                actualEscalation, List.of(),
                List.of("SANCTION_LEVEL_1_MATCH"), "SCORED".equals(status) ? List.of() : List.of("SANCTION_LEVEL_1_MATCH"),
                List.of(), List.of("MANUAL_REVIEW"), "SCORED".equals(status) ? List.of() : List.of("MANUAL_REVIEW"),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("transactionProfile", "corporateProfile", "checkSanctions", "searchLegal"),
                0, 0, List.of(), endToEndTaskPass, strictPass, 10, null, null
        );
    }
}
