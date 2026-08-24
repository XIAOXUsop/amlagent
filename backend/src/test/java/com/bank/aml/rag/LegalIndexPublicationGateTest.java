package com.bank.aml.rag;

import com.bank.aml.evaluation.RagEvaluator;
import com.bank.aml.rag.ingestion.LegalIndexPublicationGate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegalIndexPublicationGateTest {

    private final RagEvaluator evaluator = mock(RagEvaluator.class);
    private final LegalIndexVersionProvider versions = mock(LegalIndexVersionProvider.class);

    private LegalIndexPublicationGate gate() {
        return new LegalIndexPublicationGate(evaluator, versions, 90.0, 80.0, 2.0, 95.0, 95.0, 750.0);
    }

    private RagEvaluator.RagEvalReport report(double recall, double ndcg, double abstention,
                                              double noAnswer, double coldP95) {
        return new RagEvaluator.RagEvalReport(18, recall, recall, 0.95, ndcg, abstention, noAnswer,
                coldP95, 150.0, coldP95, coldP95 + 50, 120.0, 80.0, 200.0,
                RagEvaluator.SegmentedLatency.EMPTY, "v2", "hash", "REVIEWED", List.of(), "TEST");
    }

    @Test
    void passesWhenAllPublicationGatesAreMet() {
        when(versions.activeVersion()).thenReturn("");
        when(evaluator.evaluateCandidate("v-new")).thenReturn(report(95.0, 85.0, 100.0, 100.0, 200.0));

        LegalIndexPublicationGate.GateResult result = gate().evaluate("v-new", 100);

        assertThat(result.passed()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.qualityJson()).contains("\"recallAt5\":95.0");
        verify(evaluator, never()).evaluate();
    }

    @Test
    void rejectsWhenCandidateRecallBelowThreshold() {
        when(versions.activeVersion()).thenReturn("");
        when(evaluator.evaluateCandidate("v-new")).thenReturn(report(85.0, 85.0, 100.0, 100.0, 200.0));

        LegalIndexPublicationGate.GateResult result = gate().evaluate("v-new", 100);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("recallAt5"));
    }

    @Test
    void rejectsWhenColdLatencyExceedsCeiling() {
        when(versions.activeVersion()).thenReturn("");
        when(evaluator.evaluateCandidate("v-new")).thenReturn(report(95.0, 85.0, 100.0, 100.0, 1200.0));

        LegalIndexPublicationGate.GateResult result = gate().evaluate("v-new", 100);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("coldP95Ms"));
    }

    @Test
    void rejectsWhenCandidateLosesTooMuchRecallVersusActive() {
        when(versions.activeVersion()).thenReturn("v-active");
        when(evaluator.evaluateCandidate("v-new")).thenReturn(report(90.0, 85.0, 100.0, 100.0, 200.0));
        when(evaluator.evaluate()).thenReturn(report(96.0, 86.0, 100.0, 100.0, 200.0));

        LegalIndexPublicationGate.GateResult result = gate().evaluate("v-new", 100);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("recallDropVsActivePp"));
    }
}