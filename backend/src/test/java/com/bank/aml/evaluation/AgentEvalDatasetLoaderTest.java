package com.bank.aml.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEvalDatasetLoaderTest {

    private AgentEvalDatasetLoader loader;

    @BeforeEach
    void setUp() {
        loader = new AgentEvalDatasetLoader(new ObjectMapper());
    }

    @Test
    void loadsVersionedCuratedDataset() {
        AgentEvalDataset dataset = loader.load();
        var summary = loader.summary();

        assertThat(dataset.datasetId()).isEqualTo("aml-agent-curated-v1");
        assertThat(dataset.version()).isEqualTo("1.1.0");
        assertThat(dataset.sourceType()).isEqualTo("SYNTHETIC_CURATED");
        assertThat(dataset.annotationMethod()).isEqualTo("AI_ASSISTED_HUMAN_CURATED");
        assertThat(dataset.reviewStatus()).isEqualTo("PENDING_DOMAIN_REVIEW");
        assertThat(summary.totalCases()).isEqualTo(15);
        assertThat(summary.splitCounts()).containsKeys("DEV", "TEST");
        assertThat(summary.riskLevelCounts()).containsKeys("低风险", "中风险", "高风险");
    }

    @Test
    void keepsAgentCasesIndependentFromRuleRegressionCases() {
        AgentEvalDataset dataset = loader.load();
        Set<String> ids = new HashSet<>();

        assertThat(dataset.cases()).allSatisfy(evalCase -> {
            assertThat(evalCase.id()).startsWith("AML-AE-").doesNotStartWith("RULE-");
            assertThat(ids.add(evalCase.id())).isTrue();
            assertThat(evalCase.expected().requiredTools()).containsExactlyInAnyOrder(
                    "transactionProfile", "corporateProfile", "checkSanctions", "searchLegal");
            assertThat(evalCase.annotation().reviewStatus()).isEqualTo("PENDING_DOMAIN_REVIEW");
            assertThat(AgentEvalVocabulary.FINDING_CODES)
                    .containsAll(evalCase.expected().allowedFindingCodes());
            assertThat(evalCase.expected().allowedFindingCodes())
                    .containsAll(evalCase.expected().requiredFindingCodes());
            assertThat(AgentEvalVocabulary.ACTION_CODES)
                    .containsAll(evalCase.expected().allowedActions());
            assertThat(evalCase.expected().allowedActions())
                    .containsAll(evalCase.expected().requiredActions());
        });
    }

    @Test
    void testSplitContainsHardNegativeAndCriticalSanctionCases() {
        AgentEvalDataset dataset = loader.load();
        var testCases = dataset.cases().stream().filter(c -> "TEST".equals(c.split())).toList();

        assertThat(testCases).extracting(AgentEvalDataset.AgentEvalCase::scenario)
                .contains("FALSE_POSITIVE_NAME_MATCH", "LEGITIMATE_NIGHT_ACTIVITY",
                        "LEVEL_ONE_SANCTION_WITH_BENIGN_TRANSACTIONS", "UNTRUSTED_TOOL_TEXT");
        assertThat(testCases.stream().filter(c -> c.expected().mustEscalate()).toList()).isNotEmpty();
    }

    @Test
    void fixtureAndExpectedLabelsAreStoredSeparately() {
        AgentEvalDataset.AgentEvalCase evalCase = loader.load().cases().getFirst();

        assertThat(evalCase.input().caseDescription()).isNotBlank();
        assertThat(evalCase.toolFixture().transactionResult()).isNotBlank();
        assertThat(evalCase.toolFixture().legalResult()).contains("evidenceId=");
        assertThat(evalCase.expected().requiredRiskSignals()).isNotEmpty();
        assertThat(evalCase.input().caseDescription())
                .doesNotContain(evalCase.expected().riskLevel(), "requiredActions", "forbiddenClaimCodes");
    }

    @Test
    void storesStructuredGuardrailFactsForPositiveAndHardNegativeCases() {
        Map<String, AgentEvalDataset.AgentEvalCase> cases = loader.load().cases().stream()
                .collect(Collectors.toMap(AgentEvalDataset.AgentEvalCase::id, Function.identity()));

        assertThat(cases.get("AML-AE-003").toolFixture().riskFacts().transactionPatternSeverity()).isEqualTo(2);
        assertThat(cases.get("AML-AE-004").toolFixture().riskFacts().transactionPatternSeverity()).isEqualTo(2);
        assertThat(cases.get("AML-AE-009").toolFixture().riskFacts().transactionPatternSeverity()).isEqualTo(1);
        assertThat(cases.get("AML-AE-005").toolFixture().riskFacts().uboRiskSeverity()).isEqualTo(2);
        assertThat(cases.get("AML-AE-013").toolFixture().riskFacts().uboRiskSeverity()).isEqualTo(1);

        assertThat(cases.get("AML-AE-002").toolFixture().riskFacts().transactionRiskExplained()).isTrue();
        assertThat(cases.get("AML-AE-012").toolFixture().riskFacts().transactionRiskExplained()).isTrue();
        assertThat(cases.get("AML-AE-012").toolFixture().riskFacts().transactionPatternSeverity()).isZero();
        assertThat(cases.get("AML-AE-008").toolFixture().riskFacts().transactionDataComplete()).isFalse();
    }

    @Test
    void rejectsRiskSeverityOutsideClosedZeroToTwoRange() {
        AgentEvalDataset dataset = loader.load();
        AgentEvalDataset.AgentEvalCase original = dataset.cases().getFirst();
        AgentEvalDataset.RiskFacts invalidFacts = new AgentEvalDataset.RiskFacts(
                0, 0, 0, true, false, 3, 0, false, 0);
        AgentEvalDataset.ToolFixture invalidFixture = new AgentEvalDataset.ToolFixture(
                original.toolFixture().transactionResult(),
                original.toolFixture().corporateResult(),
                original.toolFixture().sanctionResult(),
                original.toolFixture().legalQuery(),
                original.toolFixture().legalQueryTerms(),
                original.toolFixture().legalResult(),
                invalidFacts);
        AgentEvalDataset.AgentEvalCase invalidCase = new AgentEvalDataset.AgentEvalCase(
                original.id(), original.split(), original.scenario(), original.difficulty(),
                original.input(), invalidFixture, original.expected(), original.annotation());
        List<AgentEvalDataset.AgentEvalCase> invalidCases = dataset.cases().stream()
                .map(evalCase -> evalCase.id().equals(original.id()) ? invalidCase : evalCase)
                .toList();
        AgentEvalDataset invalidDataset = new AgentEvalDataset(
                dataset.datasetId(), dataset.version(), dataset.description(), dataset.sourceType(),
                dataset.annotationMethod(), dataset.reviewStatus(), invalidCases);

        assertThatThrownBy(() -> loader.validate(invalidDataset))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("0-2")
                .hasMessageContaining(original.id());
    }

    @Test
    void testInputsAndFixturesDoNotLeakExpectedCodes() {
        loader.load().cases().stream().filter(c -> "TEST".equals(c.split())).forEach(evalCase -> {
            String exposed = String.join(" ", evalCase.input().caseDescription(),
                    evalCase.toolFixture().transactionResult(), evalCase.toolFixture().corporateResult(),
                    evalCase.toolFixture().sanctionResult(), evalCase.toolFixture().legalResult());
            assertThat(evalCase.expected().requiredFindingCodes()).allSatisfy(code ->
                    assertThat(exposed).doesNotContain(code));
            assertThat(evalCase.expected().requiredActions()).allSatisfy(code ->
                    assertThat(exposed).doesNotContain(code));
        });
    }
}
