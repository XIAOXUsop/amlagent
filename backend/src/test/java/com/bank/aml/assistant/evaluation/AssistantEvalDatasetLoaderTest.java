package com.bank.aml.assistant.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantEvalDatasetLoaderTest {
    @Test
    void loadsSeventySyntheticCasesWithRequiredScenarioDistribution() {
        var dataset = new AssistantEvalDatasetLoader(new ObjectMapper()).load();
        assertThat(dataset.sourceType()).isEqualTo("SYNTHETIC_ONLY");
        assertThat(dataset.cases()).hasSize(70);
        assertThat(dataset.cases()).extracting(AssistantEvalDataset.EvalCase::id).doesNotHaveDuplicates();
        assertThat(dataset.cases().stream().filter(item -> item.category().equals("ATTACK"))).hasSize(15);
    }
}
