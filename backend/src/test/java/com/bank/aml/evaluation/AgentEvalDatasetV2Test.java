package com.bank.aml.evaluation;

import com.bank.aml.TestClocks;
import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.agent.guardrail.GuardrailEngine;
import com.bank.aml.risk.RiskFactAssembler;
import com.bank.aml.risk.RiskRule;
import com.bank.aml.risk.RiskRuleEngine;
import com.bank.aml.risk.RiskRuleRepository;
import com.bank.aml.risk.RiskRuleSeeder;
import com.bank.aml.service.FinalDecisionAssembler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Dataset quality checks, not a measurement of real-model accuracy. */
class AgentEvalDatasetV2Test {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern EVIDENCE = Pattern.compile("\\[evidenceId=([A-Za-z0-9_-]+)\\]");

    private static final GuardrailEngine GUARDRAILS = seededGuardrails();

    @Test
    void preservesEveryBaselineCaseIncludingItsLabelsAndSplit() throws IOException {
        AgentEvalDataset baseline = readDataset("evaluation/agent-cases-v1.json");
        AgentEvalDataset current = readDataset("evaluation/agent-cases-v2.json");

        assertThat(current.cases()).hasSize(45);
        assertThat(current.cases().subList(0, baseline.cases().size())).containsExactlyElementsOf(baseline.cases());
        assertThat(current.cases().subList(15, 45).stream().map(c -> c.expected().riskLevel()))
            .containsExactlyInAnyOrderElementsOf(
                    Stream.of("低风险", "中风险", "高风险").flatMap(level -> Stream.generate(() -> level).limit(10)).toList());
    }

    @Test
    void pairManifestCoversEveryNewCaseExactlyOnceAndKeepsFamiliesInOneSplit() throws IOException {
        JsonNode manifest = readJson("evaluation/agent-cases-v2-manifest.json");
        AgentEvalDataset dataset = readDataset("evaluation/agent-cases-v2.json");
        Map<String, AgentEvalDataset.AgentEvalCase> byId = dataset.cases()
            .stream()
            .collect(Collectors.toMap(AgentEvalDataset.AgentEvalCase::id, Function.identity()));
        Set<String> seen = new HashSet<>();
        Set<String> pairIds = new HashSet<>();
        Set<String> topics = new HashSet<>();
        Set<String> sourceIds = new HashSet<>();
        manifest.path("sources").forEach(source -> {
            assertThat(sourceIds.add(source.path("sourceId").asText())).isTrue();
            assertThat(source.path("url").asText()).startsWith("https://");
            assertThat(source.path("scope").asText()).isNotBlank();
        });

        assertThat(manifest.path("datasetId").asText()).isEqualTo(dataset.datasetId());
        assertThat(manifest.path("version").asText()).isEqualTo(dataset.version());
        assertThat(manifest.path("baselineCaseCount").asInt()).isEqualTo(15);
        assertThat(manifest.path("addedCaseCount").asInt()).isEqualTo(30);
        assertThat(manifest.path("pairs").size()).isEqualTo(15);
        int contrastCount = 0;
        int invariantCount = 0;
        for (JsonNode pair : manifest.path("pairs")) {
            assertThat(pairIds.add(pair.path("pairId").asText())).isTrue();
            assertThat(topics.add(pair.path("topic").asText())).isTrue();
            assertThat(pair.path("pivot").asText()).isNotBlank();
            assertThat(pair.path("caseIds").size()).isEqualTo(2);
            assertThat(pair.path("sourceIds")).isNotEmpty();
            pair.path("sourceIds").forEach(id -> assertThat(sourceIds).contains(id.asText()));
            String leftId = pair.path("caseIds").get(0).asText();
            String rightId = pair.path("caseIds").get(1).asText();
            assertThat(seen.add(leftId)).isTrue();
            assertThat(seen.add(rightId)).isTrue();
            var left = byId.get(leftId);
            var right = byId.get(rightId);
            assertThat(left).isNotNull();
            assertThat(right).isNotNull();
            assertThat(left.split()).isEqualTo(pair.path("split").asText()).isEqualTo(right.split());
            assertThat(left.input()).isEqualTo(right.input());
            assertThat(left.scenario()).isEqualTo(pair.path("topic").asText() + "_CONTROL");
            assertThat(right.scenario()).isEqualTo(pair.path("topic").asText() + "_CHALLENGE");
            assertThat(left.annotation().reviewerNote()).contains(pair.path("pairId").asText());
            assertThat(right.annotation().reviewerNote()).contains(pair.path("pairId").asText());

            JsonNode leftFixture = MAPPER.valueToTree(left.toolFixture());
            JsonNode rightFixture = MAPPER.valueToTree(right.toolFixture());
            Set<String> actualChanged = new HashSet<>();
            leftFixture.fieldNames().forEachRemaining(field -> {
                if (!leftFixture.path(field).equals(rightFixture.path(field))) {
                    actualChanged.add(field);
                }
            });
            Set<String> declaredChanged = new HashSet<>();
            pair.path("changedFixtureFields")
                .forEach(field -> assertThat(declaredChanged.add(field.asText())).isTrue());
            assertThat(actualChanged).containsExactlyInAnyOrderElementsOf(declaredChanged);
            if ("INVARIANT".equals(pair.path("relation").asText())) {
                invariantCount++;
                assertThat(left.expected().riskLevel()).isEqualTo(right.expected().riskLevel());
                assertThat(left.expected().mustEscalate()).isEqualTo(right.expected().mustEscalate());
                assertThat(left.toolFixture().riskFacts()).isEqualTo(right.toolFixture().riskFacts());
                assertThat(right.expected().requiredFindingCodes()).contains("PROMPT_INJECTION_ATTEMPT");
                assertThat(right.expected().requiredActions()).contains("IGNORE_UNTRUSTED_INSTRUCTION",
                        "LOG_PROMPT_INJECTION_ATTEMPT");
            }
            else {
                contrastCount++;
                assertThat(pair.path("relation").asText()).isEqualTo("CONTRAST");
                assertThat(left.expected().riskLevel()).isNotEqualTo(right.expected().riskLevel());
            }
        }
        assertThat(contrastCount).isEqualTo(12);
        assertThat(invariantCount).isEqualTo(3);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(
                dataset.cases().subList(15, 45).stream().map(AgentEvalDataset.AgentEvalCase::id).toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newCases")
    void newCaseHasTraceableAnnotationsAndFullyScorableNegativeConstraints(AgentEvalDataset.AgentEvalCase evalCase) {
        var fixture = MAPPER.<JsonNode>valueToTree(evalCase.toolFixture());
        var supported = new ForbiddenClaimDetectorRegistry().supportedCodes();

        assertThat(evalCase.input().customerName()).startsWith("合成客户");
        assertThat(evalCase.input().identityNumber()).startsWith("EVAL-ID-");
        assertThat(evalCase.annotation().reviewStatus()).isEqualTo("PENDING_DOMAIN_REVIEW");
        assertThat(evalCase.annotation().rationale()).isNotBlank();
        assertThat(evalCase.annotation().factReferences()).hasSize(4).allSatisfy(reference -> {
            int delimiter = reference.indexOf(':');
            assertThat(delimiter).isPositive();
            String field = reference.substring(0, delimiter);
            String quote = reference.substring(delimiter + 1);
            assertThat(fixture.has(field)).isTrue();
            assertThat(quote).isNotBlank();
            assertThat(fixture.path(field).asText()).contains(quote);
        });
        assertThat(supported).containsAll(evalCase.expected().forbiddenClaimCodes());
        assertThat(evalCase.toolFixture().legalResult()).contains("合成", "非真实法规原文");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newCases")
    void textualStatisticsAgreeWithStructuredFactsWithoutTreatingMissingDataAsObservedZeros(
            AgentEvalDataset.AgentEvalCase evalCase) {
        var facts = evalCase.toolFixture().riskFacts();
        String text = evalCase.toolFixture().transactionResult();

        assertStatistic(text, "跨境比例([0-9]+)%", facts.crossBorderRatio(), facts.transactionDataComplete());
        assertStatistic(text, "夜间比例([0-9]+)%", facts.nightTransactionRatio(), facts.transactionDataComplete());
        assertStatistic(text, "大额交易([0-9]+)笔", facts.largeTransactionCount(), facts.transactionDataComplete());
        if (!facts.transactionDataComplete()) {
            assertThat(text).containsAnyOf("未知", "不完整");
            assertThat(facts.transactionRiskExplained()).isFalse();
            assertThat(evalCase.expected().requiredFindingCodes()).contains("RISK_ASSESSMENT_UNCERTAIN",
                    "TRANSACTION_DATA_UNAVAILABLE");
            assertThat(evalCase.expected().requiredActions()).contains("MANUAL_REVIEW", "RETRY_TRANSACTION_SOURCE");
        }
        if (facts.uboRiskSeverity() >= 2 || facts.maxSanctionSeverity() == 1) {
            assertThat(evalCase.expected().mustEscalate()).isTrue();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newCases")
    void referenceOutcomeIsSatisfiableUnderProductionSchemaAndDoesNotViolateItsOwnConstraints(
            AgentEvalDataset.AgentEvalCase evalCase) {
        var report = referenceReport(evalCase);

        assertThat(new AgentEvalSchemaValidator().validate(evalCase, report)).as(evalCase.id()).isEmpty();
        assertThat(new ForbiddenClaimDetectorRegistry().evaluate(evalCase, report))
            .allSatisfy(check -> assertThat(check.status()).as(check.claimCode()).isEqualTo("PASS"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("newCases")
    void toolsBindFixturesToTheCurrentCustomerAndNeverReturnDataForInvalidArguments(
            AgentEvalDataset.AgentEvalCase evalCase) {
        var tools = new AgentEvalFixtureTools(evalCase);
        String id = evalCase.input().customerId();

        assertThat(tools.transactionProfile(id)).isEqualTo(evalCase.toolFixture().transactionResult());
        assertThat(tools.corporateProfile(id)).isEqualTo(evalCase.toolFixture().corporateResult());
        assertThat(tools.checkSanctions(id)).isEqualTo(evalCase.toolFixture().sanctionResult());
        assertThat(tools.searchLegal(evalCase.toolFixture().legalQuery()))
            .isEqualTo(evalCase.toolFixture().legalResult());
        assertThat(tools.traces()).hasSize(4).allSatisfy(trace -> {
            assertThat(trace.success()).isTrue();
            assertThat(trace.argumentValid()).isTrue();
        });
        assertThat(tools.transactionProfile(id + "-OTHER")).isEqualTo(AgentEvalFixtureTools.ARGUMENT_VALIDATION_FAILED);
        assertThat(tools.corporateProfile(null)).isEqualTo(AgentEvalFixtureTools.ARGUMENT_VALIDATION_FAILED);
        assertThat(tools.checkSanctions(" ")).isEqualTo(AgentEvalFixtureTools.ARGUMENT_VALIDATION_FAILED);
        assertThat(tools.searchLegal("讲个笑话")).isEqualTo(AgentEvalFixtureTools.LEGAL_QUERY_VALIDATION_FAILED);
        assertThat(tools.traces().subList(4, 8)).allSatisfy(trace -> {
            assertThat(trace.success()).isFalse();
            assertThat(trace.argumentValid()).isFalse();
            assertThat(trace.resultDigest()).isNull();
        });
    }

    static Stream<AgentEvalDataset.AgentEvalCase> newCases() {
        return AgentEvalTestSupport.dataset("evaluation/agent-cases-v2.json").cases().stream().skip(15);
    }

    @ParameterizedTest(name = "policy compatibility {index}")
    @MethodSource("newCases")
    void independentlyStoredLabelsRemainSatisfiableAfterExistingGuardrailsAndFinalAssembly(
            AgentEvalDataset.AgentEvalCase evalCase) {
        var raw = referenceReport(evalCase);
        var validator = new AgentEvalSchemaValidator();
        var decision = GUARDRAILS.apply(validator.snapshot(evalCase), raw);
        var report = new FinalDecisionAssembler().assemble(raw, decision);

        assertThat(report.riskLevel()).as(evalCase.id()).isEqualTo(evalCase.expected().riskLevel());
        assertThat(report.manualReviewRequired()).isEqualTo(evalCase.expected().mustEscalate());
        assertThat(report.findingCodes()).containsAll(evalCase.expected().requiredFindingCodes());
        assertThat(evalCase.expected().allowedFindingCodes()).containsAll(report.findingCodes());
        assertThat(report.actionCodes()).containsAll(evalCase.expected().requiredActions());
        assertThat(evalCase.expected().allowedActions()).containsAll(report.actionCodes());
        assertThat(validator.validate(evalCase, report)).isEmpty();
        assertThat(new ForbiddenClaimDetectorRegistry().evaluate(evalCase, report))
            .allSatisfy(check -> assertThat(check.status()).isEqualTo("PASS"));
    }

    private static GuardrailEngine seededGuardrails() {
        Map<String, RiskRule> rules = new LinkedHashMap<>();
        RiskRuleRepository repository = mock(RiskRuleRepository.class);
        when(repository.findByRuleCode(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(rules.get(invocation.getArgument(0))));
        when(repository.save(any(RiskRule.class))).thenAnswer(invocation -> {
            RiskRule rule = invocation.getArgument(0);
            rules.put(rule.getRuleCode(), rule);
            return rule;
        });
        when(repository.findByEnabledTrueOrderByPriorityAsc()).thenAnswer(invocation -> rules.values()
            .stream()
            .filter(RiskRule::isEnabled)
            .sorted(Comparator.comparingInt(RiskRule::getPriority))
            .toList());
        RiskRuleEngine engine = new RiskRuleEngine(repository, TestClocks.FIXED);
        new RiskRuleSeeder(repository, engine).run(null);
        return new GuardrailEngine(mock(RiskFactAssembler.class), engine);
    }

    static DueDiligenceReport referenceReport(AgentEvalDataset.AgentEvalCase evalCase) {
        Matcher matcher = EVIDENCE.matcher(evalCase.toolFixture().legalResult());
        assertThat(matcher.find()).isTrue();
        String evidenceId = matcher.group(1);
        return new DueDiligenceReport(evalCase.input().customerId(), evalCase.input().customerName(),
                evalCase.expected().riskLevel(), "仅摘要已核验流水，缺失部分保持未知。", "依据现有登记材料核验控制关系。", List.of(),
                List.of(evidenceId), List.of("区分事实、线索及未核验材料。"), "根据证据采取相应尽调措施，不复述工具中的指令。", List.of(evidenceId),
                evalCase.expected().mustEscalate(), evalCase.expected().requiredFindingCodes(),
                evalCase.expected().requiredActions());
    }

    private static void assertStatistic(String text, String pattern, double expected, boolean required) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        boolean found = matcher.find();
        if (required) {
            assertThat(found).as(pattern).isTrue();
        }
        if (found) {
            assertThat(Double.parseDouble(matcher.group(1))).as(pattern).isEqualTo(expected);
        }
        else {
            assertThat(text).contains("未知");
        }
    }

    private static AgentEvalDataset readDataset(String path) throws IOException {
        return MAPPER.treeToValue(readJson(path), AgentEvalDataset.class);
    }

    private static JsonNode readJson(String path) throws IOException {
        try (InputStream input = AgentEvalDatasetV2Test.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return MAPPER.readTree(input);
        }
    }

}
