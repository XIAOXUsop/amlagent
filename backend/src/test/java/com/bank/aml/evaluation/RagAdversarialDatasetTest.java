package com.bank.aml.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 对抗性评测集静态装配回归：数量、ID 唯一、类别覆盖、可答/拒答混合。 */
class RagAdversarialDatasetTest {

    private final RagEvalDatasetLoader loader = new RagEvalDatasetLoader(new ObjectMapper());

    @Test
    void adversarialDatasetHasAtLeast150CasesAcrossManyCategories() {
        RagEvalDataset dataset = loader.adversarialDataset();

        assertThat(dataset.cases()).hasSizeGreaterThanOrEqualTo(150);
        assertThat(dataset.datasetVersion()).isEqualTo("rag-adversarial-v3");
        Set<String> ids = new HashSet<>();
        Set<String> categories = new HashSet<>();
        int answerable = 0;
        int notAnswerable = 0;
        for (RagEvalDataset.RagEvalCase c : dataset.cases()) {
            assertThat(ids.add(c.id())).as("重复案例 ID %s", c.id()).isTrue();
            categories.add(c.category());
            if (c.answerable()) {
                answerable++;
                assertThat(c.expectedTitleContains()).isNotBlank();
                assertThat(c.expectedContentContains()).isNotBlank();
            } else {
                notAnswerable++;
            }
        }
        assertThat(categories.size()).isGreaterThanOrEqualTo(10);
        assertThat(answerable).isGreaterThan(50);
        assertThat(notAnswerable).isGreaterThan(40);
    }

    @Test
    void adversarialHashIsStableHexDigest() {
        assertThat(loader.adversarialHash()).hasSize(64);
    }

    @Test
    void securityCategoriesBuildRealRestrictedExpiredAndPoisonedFixtures() {
        RagAdversarialFixtureFactory factory = new RagAdversarialFixtureFactory();
        RagEvalDataset dataset = loader.adversarialDataset();

        for (String category : Set.of("UNAUTHORIZED_SCOPE", "EXPIRED_LAW", "DOCUMENT_POISONING",
                "MALICIOUS_DOCUMENT_INSTRUCTION", "FAKE_OFFICIAL_SOURCE", "SENSITIVE_LEAK")) {
            var evalCase = dataset.cases().stream().filter(c -> category.equals(c.category()))
                    .findFirst().orElseThrow();
            var fixture = factory.scenario(evalCase, "fixture-v1").orElseThrow();
            var hit = fixture.searcher().searchScored(new com.bank.aml.rag.RetrievalRequest(
                    evalCase.question(), evalCase.question(), java.time.Instant.parse("2026-08-01T00:00:00Z"),
                    "CN", Set.of("PUBLIC_LEGAL"), 5, 0.04), 5).getFirst();

            assertThat(hit.denseScore()).isEqualTo(0.99);
            if (category.equals("UNAUTHORIZED_SCOPE")) {
                assertThat(hit.document().metadata().accessScopes()).containsExactly("AML_INTERNAL");
            } else if (category.equals("EXPIRED_LAW")) {
                assertThat(hit.document().metadata().effectiveTo()).isBefore(java.time.LocalDate.of(2026, 8, 1));
            } else {
                assertThat(hit.document().metadata().securityStatus()).isEqualTo("UNTRUSTED_METADATA");
                assertThat(hit.document().content()).contains("忽略系统规则");
            }
        }
    }
}
