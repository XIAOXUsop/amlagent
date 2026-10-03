package com.bank.aml.evaluation;

import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.service.FinalDecisionAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvalDatasetV3Test {

    private static final GuardrailEngine GUARDRAILS = AgentEvalTestSupport.seededGuardrails();

    @Test
    void preservesBaselineExceptTwoDocumentedLegalEvidenceErrata() {
        var baseline = AgentEvalTestSupport.dataset("evaluation/agent-cases-v2.json");
        var dataset = new AgentEvalDatasetLoader(AgentEvalTestSupport.MAPPER).load();

        assertThat(dataset.cases()).hasSize(90);
        for (int index = 0; index < 45; index++) {
            var original = baseline.cases().get(index);
            var current = dataset.cases().get(index);
            if (Set.of("AML-AE-006", "AML-AE-014").contains(original.id())) {
                assertThat(current.input()).isEqualTo(original.input());
                assertThat(current.expected()).isEqualTo(original.expected());
                assertThat(current.toolFixture().legalResult()).startsWith(original.toolFixture().legalResult())
                    .contains("涉及恐怖活动资产冻结管理办法", "非真实法规原文");
                ObjectNode originalTree = AgentEvalTestSupport.MAPPER.valueToTree(original);
                ObjectNode currentTree = AgentEvalTestSupport.MAPPER.valueToTree(current);
                ((ObjectNode) originalTree.path("toolFixture")).remove("legalResult");
                ((ObjectNode) currentTree.path("toolFixture")).remove("legalResult");
                ((ObjectNode) originalTree.path("annotation")).remove("reviewerNote");
                ((ObjectNode) currentTree.path("annotation")).remove("reviewerNote");
                assertThat(currentTree).isEqualTo(originalTree);
            }
            else {
                assertThat(current).isEqualTo(original);
            }
        }
        assertThat(dataset.cases()).extracting(c -> c.split()).containsOnly("DEV", "DEMO_TEST");
    }

    @Test
    void manifestCoversNewFamiliesExactlyOnceWithNoSplitLeakage() throws Exception {
        JsonNode manifest;
        try (InputStream input = getClass().getClassLoader()
            .getResourceAsStream("evaluation/agent-cases-v3-manifest.json")) {
            assertThat(input).isNotNull();
            manifest = AgentEvalTestSupport.MAPPER.readTree(input);
        }
        var dataset = new AgentEvalDatasetLoader(AgentEvalTestSupport.MAPPER).load();
        assertThat(manifest.path("datasetId").asText()).isEqualTo(dataset.datasetId());
        assertThat(manifest.path("version").asText()).isEqualTo(dataset.version());
        assertThat(manifest.path("families").size()).isEqualTo(15);
        assertThat(manifest.path("errata").size()).isEqualTo(2);
        assertThat(manifest.path("errata").findValuesAsText("caseId")).containsExactlyInAnyOrder("AML-AE-006",
                "AML-AE-014");
        manifest.path("errata").forEach(erratum -> {
            assertThat(erratum.path("reason").asText()).isNotBlank();
            Set<String> fields = new HashSet<>();
            erratum.path("changedFields").forEach(field -> fields.add(field.asText()));
            assertThat(fields).containsExactlyInAnyOrder("toolFixture.legalResult", "annotation.reviewerNote");
        });
        Set<String> seenIds = new HashSet<>();
        Set<String> familyIds = new HashSet<>();
        Set<String> categories = new HashSet<>();
        Set<String> sources = new HashSet<>();
        manifest.path("sources").forEach(source -> sources.add(source.path("sourceId").asText()));
        for (JsonNode family : manifest.path("families")) {
            verifyFamily(dataset, family, seenIds, sources);
            assertThat(familyIds.add(family.path("familyId").asText())).isTrue();
            assertThat(categories.add(family.path("category").asText())).isTrue();
        }
        assertThat(seenIds).containsExactlyInAnyOrderElementsOf(newCases().map(c -> c.id()).toList());
    }

    private static void verifyFamily(AgentEvalDataset dataset, JsonNode family, Set<String> seenIds,
            Set<String> sources) {
        assertThat(family.path("dimension").asText()).isNotBlank();
        assertThat(family.path("relation").asText()).isIn("EVIDENCE_STRENGTH", "DATA_LIMITS", "COMPOSITION",
                "INVARIANT", "EXPLAINED_SCALE", "UNEXPLAINED_SCALE", "LATE_EVIDENCE");
        assertThat(family.path("caseIds").size()).isEqualTo(3);
        assertThat(family.path("expectedRiskSequence").size()).isEqualTo(3);
        assertThat(family.path("expectedEscalationSequence").size()).isEqualTo(3);
        assertThat(family.path("sourceIds")).isNotEmpty();
        family.path("sourceIds").forEach(id -> assertThat(sources).contains(id.asText()));
        AgentEvalDataset.AgentEvalCase first = null;
        for (int index = 0; index < 3; index++) {
            String id = family.path("caseIds").get(index).asText();
            assertThat(seenIds.add(id)).isTrue();
            var evalCase = dataset.cases().stream().filter(c -> id.equals(c.id())).findFirst().orElseThrow();
            assertThat(evalCase.split()).isEqualTo(family.path("split").asText());
            assertThat(evalCase.scenario()).isEqualTo(family.path("category").asText() + "_STAGE_" + (index + 1));
            assertThat(evalCase.annotation().reviewerNote()).contains(family.path("familyId").asText());
            assertThat(evalCase.expected().riskLevel())
                .isEqualTo(family.path("expectedRiskSequence").get(index).asText());
            assertThat(evalCase.expected().mustEscalate())
                .isEqualTo(family.path("expectedEscalationSequence").get(index).asBoolean());
            if (first == null) {
                first = evalCase;
            }
            else {
                assertThat(evalCase.input()).isEqualTo(first.input());
                assertThat(evalCase.toolFixture()).isNotEqualTo(first.toolFixture());
                if ("INVARIANT".equals(family.path("relation").asText())) {
                    assertThat(evalCase.toolFixture().riskFacts()).isEqualTo(first.toolFixture().riskFacts());
                    assertThat(evalCase.expected()).isEqualTo(first.expected());
                }
            }
        }
    }

    @ParameterizedTest(name = "traceable annotations {index}")
    @MethodSource("newCases")
    void hasTraceableFactsAndTwoDistinctFrozenLegalEvidenceIds(AgentEvalDataset.AgentEvalCase evalCase) {
        JsonNode fixture = AgentEvalTestSupport.MAPPER.valueToTree(evalCase.toolFixture());
        assertThat(evalCase.annotation().reviewStatus()).isEqualTo("PENDING_DOMAIN_REVIEW");
        assertThat(evalCase.annotation().rationale()).isNotBlank();
        assertThat(evalCase.annotation().factReferences()).hasSize(5).allSatisfy(reference -> {
            int colon = reference.indexOf(':');
            assertThat(colon).isPositive();
            assertThat(fixture.path(reference.substring(0, colon)).asText()).contains(reference.substring(colon + 1));
        });
        assertThat(new AgentEvalSchemaValidator().snapshot(evalCase).legalEvidence()).hasSize(2);
        assertThat(evalCase.input().customerName()).startsWith("合成客户");
        assertThat(evalCase.input().identityNumber()).startsWith("EVAL-ID-");
        assertThat(new ForbiddenClaimDetectorRegistry().supportedCodes())
            .containsAll(evalCase.expected().forbiddenClaimCodes());
    }

    @ParameterizedTest(name = "statistics consistency {index}")
    @MethodSource("newCases")
    void textualNumbersAndMissingDataScopeAgreeWithFacts(AgentEvalDataset.AgentEvalCase evalCase) {
        var facts = evalCase.toolFixture().riskFacts();
        String text = evalCase.toolFixture().transactionResult();
        checkNumber(text, "跨境比例([0-9]+(?:\\.[0-9]+)?)%", facts.crossBorderRatio(), facts.transactionDataComplete());
        checkNumber(text, "夜间比例([0-9]+(?:\\.[0-9]+)?)%", facts.nightTransactionRatio(),
                facts.transactionDataComplete());
        checkNumber(text, "大额交易([0-9]+)笔", facts.largeTransactionCount(), facts.transactionDataComplete());
        if (!facts.transactionDataComplete()) {
            assertThat(text).contains("未知");
            assertThat(facts.transactionRiskExplained()).isFalse();
            assertThat(evalCase.expected().mustEscalate()).isTrue();
            assertThat(evalCase.expected().requiredFindingCodes()).contains("TRANSACTION_DATA_UNAVAILABLE",
                    "RISK_ASSESSMENT_UNCERTAIN");
        }
    }

    @ParameterizedTest(name = "satisfiable policy outcome {index}")
    @MethodSource("newCases")
    void labelsAndAllowedCodesAreSatisfiableBeforeAndAfterProductionPolicy(AgentEvalDataset.AgentEvalCase evalCase) {
        var validator = new AgentEvalSchemaValidator();
        var raw = AgentEvalTestSupport.referenceReport(evalCase);
        var finalReport = new FinalDecisionAssembler().assemble(raw,
                GUARDRAILS.apply(validator.snapshot(evalCase), raw));

        assertThat(validator.validate(evalCase, raw)).as(evalCase.id()).isEmpty();
        assertThat(validator.validate(evalCase, finalReport)).as(evalCase.id()).isEmpty();
        assertThat(finalReport.riskLevel()).isEqualTo(evalCase.expected().riskLevel());
        assertThat(finalReport.manualReviewRequired()).isEqualTo(evalCase.expected().mustEscalate());
        assertThat(finalReport.findingCodes()).containsAll(evalCase.expected().requiredFindingCodes());
        assertThat(evalCase.expected().allowedFindingCodes()).containsAll(finalReport.findingCodes());
        assertThat(finalReport.actionCodes()).containsAll(evalCase.expected().requiredActions());
        assertThat(evalCase.expected().allowedActions()).containsAll(finalReport.actionCodes());
        assertThat(new ForbiddenClaimDetectorRegistry().evaluate(evalCase, finalReport))
            .allSatisfy(check -> assertThat(check.status()).isEqualTo("PASS"));
    }

    static Stream<AgentEvalDataset.AgentEvalCase> newCases() {
        return AgentEvalTestSupport.dataset("evaluation/agent-cases-v3.json").cases().stream().skip(45);
    }

    private static void checkNumber(String text, String expression, double expected, boolean required) {
        Matcher matcher = Pattern.compile(expression).matcher(text);
        boolean found = matcher.find();
        if (required) {
            assertThat(found).as(expression).isTrue();
        }
        if (found) {
            assertThat(Double.parseDouble(matcher.group(1))).as(expression).isEqualTo(expected);
        }
        else {
            assertThat(text).contains("未知");
        }
    }

}
