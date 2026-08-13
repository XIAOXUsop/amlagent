package com.bank.aml.evaluation;

import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.config.LlmProperties;
import com.bank.aml.config.LlmProviderProperties;
import com.bank.aml.config.MockChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentEvalRunnerTest {

    @Test
    void caseInputMakesLegalKeywordContractVisibleWithoutExposingExpectedLabels() throws Exception {
        LlmProviderProperties provider = new LlmProviderProperties();
        provider.setType("mock");
        provider.setModelName("mock-test");
        LlmProperties properties = new LlmProperties();
        properties.setActiveProvider("mock");
        properties.setProviders(Map.of("mock", provider));
        AgentEvalDatasetLoader loader = new AgentEvalDatasetLoader(new ObjectMapper());
        AgentEvalRunner runner = new AgentEvalRunner(
                new MockChatModel("mock-test"), properties, loader, new AgentEvalSchemaValidator(),
                new AgentEvalScorer(), mock(GuardrailEngine.class), new ForbiddenClaimDetectorRegistry());
        AgentEvalDataset.AgentEvalCase evalCase = loader.load().cases().stream()
                .filter(candidate -> "AML-AE-001".equals(candidate.id()))
                .findFirst().orElseThrow();
        Method buildInput = AgentEvalRunner.class.getDeclaredMethod(
                "buildInput", AgentEvalDataset.AgentEvalCase.class);
        buildInput.setAccessible(true);

        String input = (String) buildInput.invoke(runner, evalCase);

        assertThat(input).contains("法规检索关键词（searchLegal 的 query 至少逐字包含一项）："
                + String.join("、", evalCase.toolFixture().legalQueryTerms()));
        assertThat(input).doesNotContain(evalCase.toolFixture().legalQuery(),
                "requiredFindingCodes", "allowedFindingCodes", "requiredActions",
                "allowedActions", "mustEscalate", "forbiddenClaimCodes");
    }

    @Test
    void rejectsMockWithoutAttemptingAnyCasesOrProducingQualityScores() {
        LlmProviderProperties provider = new LlmProviderProperties();
        provider.setType("mock");
        provider.setModelName("mock-test");
        LlmProperties properties = new LlmProperties();
        properties.setActiveProvider("mock");
        properties.setProviders(Map.of("mock", provider));

        AgentEvalRunner runner = new AgentEvalRunner(
                new MockChatModel("mock-test"), properties,
                new AgentEvalDatasetLoader(new ObjectMapper()), new AgentEvalSchemaValidator(),
                new AgentEvalScorer(), mock(GuardrailEngine.class), new ForbiddenClaimDetectorRegistry()
        );

        var status = runner.readiness();
        var report = runner.runDev();

        assertThat(status.ready()).isFalse();
        assertThat(report.runStatus()).isEqualTo("INVALID_MODEL_FALLBACK");
        assertThat(report.runtime().realModel()).isFalse();
        assertThat(report.runtime().fallbackUsed()).isTrue();
        assertThat(report.attempted()).isZero();
        assertThat(report.scored()).isZero();
        assertThat(report.rawRisk().exactAccuracy().value()).isNull();
        assertThat(report.cases()).isEmpty();
    }
}
