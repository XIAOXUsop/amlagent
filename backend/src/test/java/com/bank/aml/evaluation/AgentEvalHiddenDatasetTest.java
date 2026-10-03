package com.bank.aml.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Approval flags here are deliberately synthetic fixtures, not real expert approval. */
class AgentEvalHiddenDatasetTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void rejectsUnapprovedDatasetEvenWhenItsCasesClaimApproval() throws Exception {
        Path path = writeDataset("PENDING_DOMAIN_REVIEW", "DOMAIN_EXPERT_APPROVED", "HIDDEN-SYNTHETIC-001", "TEST");

        assertThatThrownBy(() -> loader(path).load()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("数据集必须完成领域专家审批");
    }

    @Test
    void rejectsUnapprovedCaseInsideApprovedDataset() throws Exception {
        Path path = writeDataset("DOMAIN_EXPERT_APPROVED", "PENDING_DOMAIN_REVIEW", "HIDDEN-SYNTHETIC-001", "TEST");

        assertThatThrownBy(() -> loader(path).load()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("每条案例都必须完成领域专家审批");
    }

    @Test
    void rejectsPublicSplitInExternalHiddenDataset() throws Exception {
        Path path = writeDataset("DOMAIN_EXPERT_APPROVED", "DOMAIN_EXPERT_APPROVED", "HIDDEN-SYNTHETIC-001", "DEV");

        assertThatThrownBy(() -> loader(path).load()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("split 非法");
    }

    @Test
    void rejectsPublicCaseIdCollision() throws Exception {
        Path path = writeDataset("DOMAIN_EXPERT_APPROVED", "DOMAIN_EXPERT_APPROVED", "AML-AE-001", "TEST");

        assertThatThrownBy(() -> loader(path).load()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("案例 ID 重复");
    }

    @Test
    void approvedHiddenFixtureHasItsOwnHashAndDoesNotChangePublicCaseCount() throws Exception {
        var publicLoader = new AgentEvalDatasetLoader(mapper);
        Path path = writeDataset("DOMAIN_EXPERT_APPROVED", "DOMAIN_EXPERT_APPROVED", "HIDDEN-SYNTHETIC-001", "TEST");
        var combined = loader(path);

        assertThat(combined.hasApprovedHiddenTest()).isTrue();
        assertThat(combined.summary().totalCases()).isEqualTo(91);
        assertThat(combined.summary().splitCounts()).containsEntry("DEV", 59L)
            .containsEntry("DEMO_TEST", 31L)
            .containsEntry("TEST", 1L);
        assertThat(combined.summary().hiddenTestDatasetHash()).matches("[0-9a-f]{64}");
        assertThat(combined.datasetHash()).isNotEqualTo(publicLoader.datasetHash())
            .isEqualTo(loader(path).datasetHash());
        assertThat(combined.load().cases().subList(0, 90)).containsExactlyElementsOf(publicLoader.load().cases());
        assertThat(publicLoader.hasApprovedHiddenTest()).isFalse();
    }

    @Test
    void absentExternalDatasetFailsClosed() {
        Path path = directory.resolve("not-present.json");

        assertThatThrownBy(() -> loader(path).load()).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("无法加载 Agent 评测数据集");
    }

    private AgentEvalDatasetLoader loader(Path path) {
        return new AgentEvalDatasetLoader(mapper, path.toString());
    }

    private Path writeDataset(String datasetReview, String caseReview, String id, String split) throws Exception {
        var original = new AgentEvalDatasetLoader(mapper).load().cases().getFirst();
        var annotation = new AgentEvalDataset.Annotation("Synthetic approval-gate fixture only.",
                original.annotation().factReferences(), caseReview, "No real domain expert has reviewed this fixture.");
        var evalCase = new AgentEvalDataset.AgentEvalCase(id, split, original.scenario(), original.difficulty(),
                original.input(), original.toolFixture(), original.expected(), annotation);
        var dataset = new AgentEvalDataset("synthetic-hidden-gate-test", "test-only",
                "Synthetic fixture, not a usable hidden benchmark.", "SYNTHETIC_CURATED", "TEST_ONLY", datasetReview,
                List.of(evalCase));
        Path path = directory.resolve("hidden.json");
        mapper.writeValue(path.toFile(), dataset);
        return path;
    }

}
