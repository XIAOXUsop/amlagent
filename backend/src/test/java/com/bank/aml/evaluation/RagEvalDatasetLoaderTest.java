package com.bank.aml.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvalDatasetLoaderTest {

    @Test
    void loadsVersionedIndependentDatasetWithAnswerableAndNoAnswerCases() {
        RagEvalDatasetLoader loader = new RagEvalDatasetLoader(new ObjectMapper());

        assertThat(loader.dataset().datasetVersion()).isEqualTo("rag-legal-dev-v2");
        assertThat(loader.dataset().cases()).hasSize(18)
                .anyMatch(RagEvalDataset.RagEvalCase::answerable)
                .anyMatch(c -> !c.answerable());
        assertThat(loader.datasetHash()).hasSize(64);
        assertThat(loader.dataset().cases()).extracting(RagEvalDataset.RagEvalCase::question)
                .doesNotHaveDuplicates();
    }
}
