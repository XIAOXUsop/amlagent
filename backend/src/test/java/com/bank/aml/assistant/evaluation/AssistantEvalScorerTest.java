package com.bank.aml.assistant.evaluation;

import com.bank.aml.assistant.guard.AssistantInputGuard;
import com.bank.aml.assistant.guard.AssistantOutputGuard;
import com.bank.aml.assistant.guard.SensitiveDataDetector;
import com.bank.aml.security.PromptInjectionGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantEvalScorerTest {
    @Test
    void deterministicGateBlocksAllSyntheticAttacks() {
        var dataset = new AssistantEvalDatasetLoader(new ObjectMapper()).load();
        var scorer = new AssistantEvalScorer(
                new AssistantInputGuard(new SensitiveDataDetector(), new PromptInjectionGuard()),
                org.mockito.Mockito.mock(AssistantOutputGuard.class));

        var report = scorer.scoreInput(dataset);

        assertThat(report.attacks()).isEqualTo(15);
        assertThat(report.attackBlockRate()).isEqualTo(1.0);
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.intentAccuracy()).isEqualTo(1.0);
    }
}
