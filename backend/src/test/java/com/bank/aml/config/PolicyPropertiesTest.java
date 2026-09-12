package com.bank.aml.config;

import com.bank.aml.TestProperties;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyPropertiesTest {

    @Test
    void defaultPoliciesAreValidAndPreservePublishedBaselines() {
        List<Object> properties = List.of(TestProperties.aml(), new AgentOutputProperties(), new AuditProperties(),
                new ExplanationProperties(), new OperationsProperties(), new RiskProperties(),
                new WorkflowProperties());

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(properties).allSatisfy(value -> assertThat(factory.getValidator().validate(value)).isEmpty());
            RagProperties rag = new RagProperties();
            assertThat(factory.getValidator().validate(rag.getRetrieval())).isEmpty();
            assertThat(factory.getValidator().validate(rag.getIngestion())).isEmpty();
        }

        assertThat(new OperationsProperties().scenarioWeight("SANCTIONS_WATCHLIST")).isEqualTo(80);
        assertThat(new RiskProperties().getTransaction().getLargeAmount())
            .isEqualByComparingTo(new BigDecimal("1000000"));
    }

    @Test
    void rejectsInconsistentCrossFieldPolicies() {
        RagProperties rag = new RagProperties();
        rag.getIngestion().setBuildLeaseMinutes(1);
        rag.getIngestion().setLeaseHeartbeatSeconds(60);
        rag.getRetrieval().setMaxRecalledCandidates(2);
        rag.getFusion().setLexicalScoreBonus(0.30);
        rag.getRerank().setMaxConcurrency(1);
        rag.getRerank().setInferenceExecutorThreads(2);

        OperationsProperties operations = new OperationsProperties();
        operations.setCriticalThreshold(50);
        operations.setHighThreshold(60);

        RiskProperties risk = new RiskProperties();
        risk.getTransaction().setHighlightedAmount(new BigDecimal("2000000"));
        risk.getSanction().setHighNameSimilarityScore(99);

        AmlProperties.Data data = new AmlProperties.Data("sync-v1", 1_000, 999, 100, 50);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(rag)).extracting(v -> v.getPropertyPath().toString())
                .contains("ingestion.buildLeaseWindowValid", "retrieval.recallCapacityValid",
                        "fusion.lexicalScoreBonus", "rerank.inferenceConcurrencyValid");
            assertThat(factory.getValidator().validate(operations)).extracting(v -> v.getPropertyPath().toString())
                .contains("priorityThresholdOrderValid");
            assertThat(factory.getValidator().validate(risk)).extracting(v -> v.getPropertyPath().toString())
                .contains("transaction.amountThresholdOrderValid", "sanction.scoreOrderValid");
            assertThat(factory.getValidator().validate(data)).extracting(v -> v.getPropertyPath().toString())
                .contains("customerImportRequestCapacityValid");
        }
    }

}
