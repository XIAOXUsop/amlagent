package com.bank.aml.evaluation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 用真实 MySQL 发布指针、PGVector 与固定独立 DEV 集验证端到端 RAG 评测链路。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class RagEvaluationIntegrationTest {
    private static final String SUFFIX = java.util.UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedInfrastructure(DynamicPropertyRegistry registry) {
        com.bank.aml.testinfra.IntegrationTestDatabase.configure(registry, "aml_rag_evaluation_test");
        registry.add("aml.queue.stream", () -> "aml:workflow:cases-rag-eval-" + SUFFIX);
        registry.add("aml.queue.dead-stream", () -> "aml:workflow:dead-rag-eval-" + SUFFIX);
        registry.add("aml.queue.group", () -> "aml-workers-rag-eval-" + SUFFIX);
    }

    @Autowired RagEvaluator evaluator;

    @Test
    void evaluatesFixedDatasetThroughNoAnswerGate() {
        RagEvaluator.RagEvalReport report = evaluator.evaluate();

        assertThat(report.totalCases()).isEqualTo(18);
        assertThat(report.datasetVersion()).isEqualTo("rag-legal-dev-v2");
        assertThat(report.datasetHash()).hasSize(64);
        assertThat(report.reviewStatus()).isEqualTo("PENDING_DOMAIN_REVIEW");
        assertThat(report.recallAt5()).isBetween(0.0, 100.0);
        assertThat(report.ndcgAt5()).isBetween(0.0, 100.0);
        assertThat(report.abstentionAccuracy()).isBetween(0.0, 100.0);
        assertThat(report.noAnswerRefusalRate()).isBetween(0.0, 100.0);
        assertThat(report.details()).hasSize(18);
        System.out.printf("RAG_EVAL_V2 recallAt5=%.1f top3=%.1f mrr=%.1f ndcg=%.1f abstention=%.1f noAnswerRefusal=%.1f p95=%.1fms%n",
                report.recallAt5(), report.top3HitRate(), report.mrr(), report.ndcgAt5(),
                report.abstentionAccuracy(), report.noAnswerRefusalRate(), report.p95DurationMs());
        report.details().stream()
                .filter(c -> (c.answerable() && c.rank() < 1) || (!c.answerable() && !c.abstained()))
                .forEach(c -> System.out.printf("RAG_EVAL_MISS id=%s answerable=%s rank=%d abstained=%s status=%s ids=%s scores=%s%n",
                        c.id(), c.answerable(), c.rank(), c.abstained(), c.retrievalStatus(),
                        c.returnedEvidenceIds(), c.relevanceScores()));
    }
}
