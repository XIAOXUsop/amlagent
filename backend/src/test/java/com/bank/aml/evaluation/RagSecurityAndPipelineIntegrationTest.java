package com.bank.aml.evaluation;

import com.bank.aml.rag.RetrievalPipeline;
import com.bank.aml.rag.rerank.BgeRerankerScoringModel;
import com.bank.aml.testinfra.IntegrationTestDatabase;
import java.util.EnumMap;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实基础设施上的 151 条安全对抗回归与四路检索管线 A/B。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class RagSecurityAndPipelineIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RagSecurityAndPipelineIntegrationTest.class);

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedInfrastructure(DynamicPropertyRegistry registry) {
        IntegrationTestDatabase.configure(registry, "aml_rag_security_ab_test");
        registry.add("aml.queue.stream", () -> "aml:workflow:cases-rag-security-" + SUFFIX);
        registry.add("aml.queue.dead-stream", () -> "aml:workflow:dead-rag-security-" + SUFFIX);
        registry.add("aml.queue.group", () -> "aml-workers-rag-security-" + SUFFIX);
        // A/B 必须执行真实本地 Cross-Encoder；禁用时 HYBRID_RERANK 会静默退化为 HYBRID，
        // 指标看似完整却没有验证重排路径。
        registry.add("aml.rag.rerank.enabled", () -> "true");
    }

    @Autowired
    RagEvaluator evaluator;

    @Autowired
    BgeRerankerScoringModel reranker;

    @Test
    void runs151AdversarialCasesWithConcreteSecurityFixtures() {
        var report = evaluator.evaluateAdversarial();
        long fixtures = report.details().stream().filter(RagEvaluator.PerCase::fixtureApplied).count();

        assertThat(report.totalCases()).isEqualTo(151);
        assertThat(fixtures).isGreaterThanOrEqualTo(50);
        assertThat(report.details().stream().filter(RagEvaluator.PerCase::fixtureApplied))
            .allMatch(RagEvaluator.PerCase::fixtureExpectationMatched);
        report.details()
            .stream()
            .filter(c -> !c.answerable() && !c.abstained())
            .forEach(c -> LOGGER.warn("RAG_ADVERSARIAL_REFUSAL_MISS id={} category={} status={} ids={}", c.id(),
                    c.category(), c.retrievalStatus(), c.returnedEvidenceIds()));
        assertThat(report.noAnswerRefusalRate()).isEqualTo(100.0);
        LOGGER.info("RAG_ADVERSARIAL total={} fixtures={} refusal={} abstention={} recall={}", report.totalCases(),
                fixtures, report.noAnswerRefusalRate(), report.abstentionAccuracy(), report.recallAt5());
    }

    @Test
    void comparesDenseLexicalHybridAndRerankPipelines() {
        assertThat(reranker.isAvailable()).as("本地 bge-reranker 必须已加载，禁止伪 A/B").isTrue();
        var reports = new EnumMap<RetrievalPipeline, RagEvaluator.RagEvalReport>(RetrievalPipeline.class);
        for (RetrievalPipeline pipeline : RetrievalPipeline.values()) {
            reports.put(pipeline, evaluator.evaluatePipeline(pipeline));
        }

        assertThat(reports).containsOnlyKeys(RetrievalPipeline.values());
        assertThat(reports.values()).allMatch(report -> report.totalCases() == 18);
        reports.forEach((pipeline, report) -> LOGGER.info(
                "RAG_AB pipeline={} recall={} top3={} mrr={} ndcg={} refusal={} coldP95={}", pipeline,
                report.recallAt5(), report.top3HitRate(), report.mrr(), report.ndcgAt5(), report.noAnswerRefusalRate(),
                report.coldP95Ms()));
    }

}
