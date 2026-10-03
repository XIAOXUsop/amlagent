package com.bank.aml.evaluation;

import com.bank.aml.TestClocks;
import com.bank.aml.TestProperties;
import com.bank.aml.config.LlmProperties;
import com.bank.aml.config.LlmProviderProperties;
import com.bank.aml.evaluation.AgentEvalDataset.AgentEvalCase;
import com.bank.aml.evaluation.AgentEvalReport.CaseResult;
import com.bank.aml.service.FinalDecisionAssembler;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises actual AiServices tool loops and scoring with scripted in-process outputs.
 * This deliberately invokes the per-case unit, not the public real-model benchmark.
 */
class AgentEvalRunnerWorkflowTest {

    @ParameterizedTest(name = "actual tool loop {index}")
    @MethodSource("allCases")
    void executesAllFourToolsAndScoresReferenceOutputThroughFullPerCasePipeline(AgentEvalCase evalCase)
            throws Exception {
        var model = script(allCalls(evalCase), referenceJson(evalCase));

        CaseResult result = executeCase(evalCase, model);

        assertThat(result.status()).as("%s: %s", evalCase.id(), result.schemaViolations()).isEqualTo("SCORED");
        assertThat(result.endToEndTaskPass()).as(evalCase.id()).isTrue();
        assertThat(result.missingTools()).isEmpty();
        assertThat(result.invalidArgumentCalls()).isZero();
        assertThat(result.duplicateCalls()).isZero();
        assertThat(result.missingEvidenceIds()).isEmpty();
        assertThat(result.toolCalls()).hasSize(4).allSatisfy(trace -> {
            assertThat(trace.success()).isTrue();
            assertThat(trace.argumentValid()).isTrue();
        });
        assertThat(model.requests()).hasSize(2);
        assertThat(model.requests().getFirst().toolSpecifications()).extracting(tool -> tool.name())
            .containsExactlyInAnyOrder("transactionProfile", "corporateProfile", "checkSanctions", "searchLegal");
        assertThat(model.requests()
            .get(1)
            .messages()
            .stream()
            .filter(message -> message instanceof ToolExecutionResultMessage)
            .map(message -> ((ToolExecutionResultMessage) message).text())
            .toList()).containsExactlyInAnyOrder(evalCase.toolFixture().transactionResult(),
                    evalCase.toolFixture().corporateResult(), evalCase.toolFixture().sanctionResult(),
                    evalCase.toolFixture().legalResult());
    }

    @Test
    void omittedToolCannotPassEvenWithCorrectRiskAndFindings() throws Exception {
        var evalCase = caseById("AML-AE-046");
        var calls = allCalls(evalCase).stream().filter(call -> !"checkSanctions".equals(call.name())).toList();

        CaseResult result = executeCase(evalCase, script(calls, referenceJson(evalCase)));

        assertThat(result.status()).isEqualTo("SCORED");
        assertThat(result.missingTools()).containsExactly("checkSanctions");
        assertThat(result.endToEndTaskPass()).isFalse();
        assertThat(result.strictPass()).isFalse();
    }

    @Test
    void wrongCustomerNeverReturnsFixtureAndRemainsFailureAfterSuccessfulRetry() throws Exception {
        var evalCase = caseById("AML-AE-046");
        List<ToolExecutionRequest> calls = new ArrayList<>(allCalls(evalCase));
        calls.set(0, customerCall("transactionProfile", "OTHER-CUSTOMER", "wrong"));
        var model = new ScriptedAgentChatModel(List.of(ScriptedAgentChatModel.toolCalls(calls),
                ScriptedAgentChatModel
                    .toolCalls(List.of(customerCall("transactionProfile", evalCase.input().customerId(), "retry"))),
                ScriptedAgentChatModel.finalText(referenceJson(evalCase))));

        CaseResult result = executeCase(evalCase, model);

        assertThat(result.status()).isEqualTo("SCORED");
        assertThat(result.missingTools()).isEmpty();
        assertThat(result.invalidArgumentCalls()).isEqualTo(1);
        assertThat(result.endToEndTaskPass()).isFalse();
        assertThat(result.toolCalls()).anySatisfy(trace -> {
            assertThat(trace.success()).isFalse();
            assertThat(trace.argumentValid()).isFalse();
            assertThat(trace.resultDigest()).isNull();
        });
        assertThat(model.requests()
            .get(1)
            .messages()
            .stream()
            .filter(message -> message instanceof ToolExecutionResultMessage)
            .map(message -> ((ToolExecutionResultMessage) message).text())
            .toList()).contains(AgentEvalFixtureTools.ARGUMENT_VALIDATION_FAILED);
    }

    @Test
    void repeatedLegalCallPassesTaskButFailsStrictEfficiencyContract() throws Exception {
        var evalCase = caseById("AML-AE-046");
        List<ToolExecutionRequest> calls = new ArrayList<>(allCalls(evalCase));
        calls.add(legalCall(evalCase.toolFixture().legalQuery(), "duplicate"));

        CaseResult result = executeCase(evalCase, script(calls, referenceJson(evalCase)));

        assertThat(result.endToEndTaskPass()).isTrue();
        assertThat(result.strictPass()).isFalse();
        assertThat(result.duplicateCalls()).isEqualTo(1);
    }

    @Test
    void toolExecutionFailureCannotCountAsSuccessfulCollection() throws Exception {
        ObjectNode fixture = AgentEvalTestSupport.MAPPER.valueToTree(caseById("AML-AE-046"));
        // Deliberate fault injection outside the loader; null payloads cannot enter the
        // published dataset.
        ((ObjectNode) fixture.path("toolFixture")).putNull("transactionResult");
        AgentEvalCase evalCase = AgentEvalTestSupport.MAPPER.treeToValue(fixture, AgentEvalCase.class);
        var model = script(allCalls(evalCase), referenceJson(evalCase));

        CaseResult result = executeCase(evalCase, model);

        assertThat(result.status()).isEqualTo("SCORED");
        assertThat(result.missingTools()).containsExactly("transactionProfile");
        assertThat(result.invalidArgumentCalls()).isZero();
        assertThat(result.endToEndTaskPass()).isFalse();
        assertThat(result.toolCalls()).anySatisfy(trace -> {
            assertThat(trace.toolName()).isEqualTo("transactionProfile");
            assertThat(trace.argumentValid()).isTrue();
            assertThat(trace.success()).isFalse();
            assertThat(trace.error()).isEqualTo(AgentEvalFixtureTools.TOOL_EXECUTION_FAILED);
            assertThat(trace.resultDigest()).isNull();
        });
        assertThat(model.requests()
            .get(1)
            .messages()
            .stream()
            .filter(message -> message instanceof ToolExecutionResultMessage)
            .map(message -> ((ToolExecutionResultMessage) message).text())
            .toList()).contains(AgentEvalFixtureTools.TOOL_EXECUTION_FAILED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenOutputs")
    void invalidOutputFailsClosedAndStaysInScoreDenominator(String name, String status, String mutation)
            throws Exception {
        var evalCase = caseById("AML-AE-046");
        String text = brokenJson(evalCase, mutation);

        CaseResult result = executeCase(evalCase, script(allCalls(evalCase), text));

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.endToEndTaskPass()).isFalse();
        assertThat(result.strictPass()).isFalse();
        var aggregate = new AgentEvalScorer().aggregate(List.of(result));
        assertThat(aggregate.taskPassRate().denominator()).isEqualTo(1);
        assertThat(aggregate.taskPassRate().numerator()).isZero();
        assertThat(aggregate.rawRisk().exactAccuracy().value()).isEqualTo(0.0);
    }

    static Stream<Arguments> brokenOutputs() {
        return Stream.of(Arguments.of("malformed JSON", "OUTPUT_PARSE_ERROR", "MALFORMED"),
                Arguments.of("invented action", "SCHEMA_INVALID", "ACTION"),
                Arguments.of("fabricated evidence", "SCHEMA_INVALID", "EVIDENCE"),
                Arguments.of("identity leakage", "SCHEMA_INVALID", "IDENTITY"));
    }

    @Test
    void modelTransportFailureDoesNotDisappearFromHighRiskRecall() throws Exception {
        var evalCase = caseById("AML-AE-048");
        var model = new ScriptedAgentChatModel(List.of(ScriptedAgentChatModel.toolCalls(allCalls(evalCase))));

        CaseResult result = executeCase(evalCase, model);

        assertThat(result.status()).isEqualTo("MODEL_ERROR");
        assertThat(result.endToEndTaskPass()).isFalse();
        assertThat(result.toolCalls()).hasSize(4);
        var aggregate = new AgentEvalScorer().aggregate(List.of(result));
        assertThat(aggregate.rawRisk().highRiskRecall().denominator()).isEqualTo(1);
        assertThat(aggregate.rawRisk().highRiskRecall().numerator()).isZero();
        assertThat(aggregate.rawRisk().criticalMissCount()).isEqualTo(1);
    }

    @Test
    void illegalLegalQueryCannotBeRepairedByClaimingANonexistentCitation() throws Exception {
        var evalCase = caseById("AML-AE-046");
        List<ToolExecutionRequest> calls = new ArrayList<>(allCalls(evalCase));
        calls.set(3, legalCall("讲个笑话", "invalid-query"));
        ObjectNode output = outputNode(evalCase);
        output.putArray("legalBasis").add("一般尽调");
        output.putArray("evidenceChain").add("一般核验");

        CaseResult result = executeCase(evalCase, script(calls, output.toString()));

        assertThat(result.status()).isEqualTo("SCHEMA_INVALID");
        assertThat(result.schemaViolations()).contains("LEGAL_EVIDENCE_ID_MISSING");
        assertThat(result.missingTools()).contains("searchLegal");
        assertThat(result.invalidArgumentCalls()).isEqualTo(1);
        assertThat(result.endToEndTaskPass()).isFalse();
    }

    @Test
    void roundLimitStopsAnEndlessToolLoop() throws Exception {
        var evalCase = caseById("AML-AE-046");
        List<ChatResponse> responses = Stream.iterate(0, index -> index + 1)
            .limit(12)
            .map(index -> ScriptedAgentChatModel
                .toolCalls(List.of(customerCall("transactionProfile", evalCase.input().customerId(), "loop-" + index))))
            .toList();
        var model = new ScriptedAgentChatModel(responses);

        CaseResult result = executeCase(evalCase, model);

        assertThat(result.status()).isEqualTo("MODEL_ERROR");
        assertThat(result.endToEndTaskPass()).isFalse();
        assertThat(model.requests().size()).isLessThanOrEqualTo(TestProperties.aml().agent().maxToolRoundTrips() + 1);
    }

    @Test
    void guardrailRescueDoesNotBecomeRawModelSuccess() throws Exception {
        var evalCase = caseById("AML-AE-048");
        ObjectNode output = outputNode(evalCase);
        output.put("riskLevel", "低风险");

        CaseResult result = executeCase(evalCase, script(allCalls(evalCase), output.toString()));

        assertThat(result.status()).isEqualTo("SCORED");
        assertThat(result.rawRiskCorrect()).isFalse();
        assertThat(result.finalRiskCorrect()).isTrue();
        assertThat(result.endToEndTaskPass()).isTrue();
        assertThat(result.strictPass()).isFalse();
        assertThat(result.triggeredGuardrailRules()).contains("TXN_PATTERN_HIGH");
    }

    private static String brokenJson(AgentEvalCase evalCase, String mutation) {
        if ("MALFORMED".equals(mutation)) {
            return "{";
        }
        ObjectNode output = outputNode(evalCase);
        switch (mutation) {
            case "ACTION" -> output.withArray("actionCodes").add("INVENTED_ACTION");
            case "EVIDENCE" -> output.withArray("legalBasis").add("AML-LEGAL-INVENTED");
            case "IDENTITY" -> output.put("conclusion", evalCase.input().identityNumber());
            default -> throw new IllegalArgumentException(mutation);
        }
        return output.toString();
    }

    private static CaseResult executeCase(AgentEvalCase evalCase, ScriptedAgentChatModel model) throws Exception {
        LlmProviderProperties provider = new LlmProviderProperties();
        provider.setType("mock");
        LlmProperties properties = new LlmProperties();
        properties.setActiveProvider("offline-test");
        properties.setProviders(Map.of("offline-test", provider));
        var runner = new AgentEvalRunner(model, properties, new AgentEvalDatasetLoader(AgentEvalTestSupport.MAPPER),
                new AgentEvalSchemaValidator(), new AgentEvalScorer(), AgentEvalTestSupport.seededGuardrails(),
                new FinalDecisionAssembler(), new ForbiddenClaimDetectorRegistry(), TestClocks.FIXED,
                TestProperties.aml());
        Method runCase = AgentEvalRunner.class.getDeclaredMethod("runCase", AgentEvalCase.class);
        runCase.setAccessible(true);
        return (CaseResult) runCase.invoke(runner, evalCase);
    }

    private static ScriptedAgentChatModel script(List<ToolExecutionRequest> calls, String text) {
        return new ScriptedAgentChatModel(
                List.of(ScriptedAgentChatModel.toolCalls(calls), ScriptedAgentChatModel.finalText(text)));
    }

    private static List<ToolExecutionRequest> allCalls(AgentEvalCase evalCase) {
        String id = evalCase.input().customerId();
        return List.of(customerCall("transactionProfile", id, "transaction"),
                customerCall("corporateProfile", id, "corp"), customerCall("checkSanctions", id, "sanctions"),
                legalCall(evalCase.toolFixture().legalQuery(), "legal"));
    }

    private static ToolExecutionRequest customerCall(String tool, String customerId, String callId) {
        return ToolExecutionRequest.builder()
            .id(callId)
            .name(tool)
            .arguments(AgentEvalTestSupport.MAPPER.valueToTree(Map.of("customerId", customerId)).toString())
            .build();
    }

    private static ToolExecutionRequest legalCall(String query, String callId) {
        return ToolExecutionRequest.builder()
            .id(callId)
            .name("searchLegal")
            .arguments(AgentEvalTestSupport.MAPPER.valueToTree(Map.of("query", query)).toString())
            .build();
    }

    private static String referenceJson(AgentEvalCase evalCase) {
        return outputNode(evalCase).toString();
    }

    private static ObjectNode outputNode(AgentEvalCase evalCase) {
        return AgentEvalTestSupport.MAPPER
            .valueToTree(AgentEvalTestSupport.analysis(AgentEvalTestSupport.referenceReport(evalCase)));
    }

    private static AgentEvalCase caseById(String id) {
        return allCases().filter(evalCase -> id.equals(evalCase.id())).findFirst().orElseThrow();
    }

    static Stream<AgentEvalCase> allCases() {
        return AgentEvalTestSupport.dataset("evaluation/agent-cases-v3.json").cases().stream();
    }

}
