package com.bank.aml.evaluation;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvalFixtureToolsTest {

    @Test
    void exposesExactlyTheFourStableToolNames() {
        List<String> toolNames = List.of(AgentEvalFixtureTools.class.getDeclaredMethods()).stream()
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(method -> method.getAnnotation(Tool.class).name())
                .sorted()
                .toList();

        assertThat(toolNames).containsExactly(
                "checkSanctions", "corporateProfile", "searchLegal", "transactionProfile");
    }

    @Test
    void returnsCurrentCaseFixturesAndCapturesSuccessfulTraces() {
        AgentEvalFixtureTools tools = new AgentEvalFixtureTools(evalCase("E1001", "Alice", "ID-001"));

        assertThat(tools.transactionProfile("E1001")).isEqualTo("TX-E1001-SECRET");
        assertThat(tools.corporateProfile("E1001")).isEqualTo("CORP-E1001-SECRET");
        assertThat(tools.checkSanctions("E1001")).isEqualTo("SANCTION-E1001-SECRET");
        assertThat(tools.searchLegal("customer due diligence")).isEqualTo("LEGAL-E1001-SECRET");

        assertThat(tools.traces()).hasSize(4).allSatisfy(trace -> {
            assertThat(trace.success()).isTrue();
            assertThat(trace.argumentValid()).isTrue();
            assertThat(trace.durationMs()).isGreaterThanOrEqualTo(0L);
            assertThat(trace.resultDigest()).hasSize(64);
            assertThat(trace.error()).isNull();
        });
        assertThat(tools.traces()).extracting(AgentEvalToolCallTrace::toolName)
                .containsExactly("transactionProfile", "corporateProfile", "checkSanctions", "searchLegal");
        assertThat(tools.traces().getFirst().arguments()).containsEntry("customerId", "[REDACTED]");
        assertThat(tools.traces().get(2).arguments()).containsEntry("customerId", "[REDACTED]");
    }

    @Test
    void rejectsMismatchedArgumentsWithoutReturningAnyFixtureData() {
        AgentEvalFixtureTools caseOne = new AgentEvalFixtureTools(evalCase("E1001", "Alice", "ID-001"));
        AgentEvalFixtureTools caseTwo = new AgentEvalFixtureTools(evalCase("E2002", "Bob", "ID-002"));

        List<String> invalidResults = List.of(
                caseOne.transactionProfile("E2002"),
                caseOne.corporateProfile("e1001"),
                caseOne.checkSanctions("E2002"),
                caseOne.checkSanctions(null)
        );

        assertThat(invalidResults).containsOnly(AgentEvalFixtureTools.ARGUMENT_VALIDATION_FAILED);
        assertThat(invalidResults).allSatisfy(result -> assertThat(result)
                .doesNotContain("E1001-SECRET", "E2002-SECRET"));
        assertThat(caseOne.searchLegal("  ")).isEqualTo(AgentEvalFixtureTools.LEGAL_QUERY_VALIDATION_FAILED);
        assertThat(caseOne.searchLegal("讲一个笑话"))
                .isEqualTo(AgentEvalFixtureTools.LEGAL_QUERY_VALIDATION_FAILED);
        assertThat(caseOne.traces()).hasSize(6).allSatisfy(trace -> {
            assertThat(trace.success()).isFalse();
            assertThat(trace.argumentValid()).isFalse();
            assertThat(trace.resultDigest()).isNull();
            assertThat(trace.error()).isEqualTo(AgentEvalFixtureTools.ARGUMENT_VALIDATION_FAILED);
        });
        assertThat(caseTwo.traces()).isEmpty();
    }

    @Test
    void legalSearchUsesVisibleKeywordContractAndRejectsParaphrases() {
        AgentEvalFixtureTools tools = new AgentEvalFixtureTools(evalCase("E1001", "Alice", "ID-001"));

        assertThat(tools.searchLegal("customer due diligence obligations")).isEqualTo("LEGAL-E1001-SECRET");
        assertThat(tools.searchLegal("a different but plausible compliance query"))
                .isEqualTo(AgentEvalFixtureTools.LEGAL_QUERY_VALIDATION_FAILED);

        assertThat(tools.traces()).extracting(AgentEvalToolCallTrace::argumentValid)
                .containsExactly(true, false);
    }

    @Test
    void capturesConcurrentCallsWithoutDroppingTraces() throws Exception {
        AgentEvalFixtureTools tools = new AgentEvalFixtureTools(evalCase("E1001", "Alice", "ID-001"));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(ignored -> executor.submit(() -> tools.transactionProfile("E1001")))
                    .toList();

            for (Future<String> future : futures) {
                assertThat(future.get()).isEqualTo("TX-E1001-SECRET");
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(tools.traces()).hasSize(64).allSatisfy(trace -> {
            assertThat(trace.toolName()).isEqualTo("transactionProfile");
            assertThat(trace.success()).isTrue();
            assertThat(trace.argumentValid()).isTrue();
        });
    }

    private static AgentEvalDataset.AgentEvalCase evalCase(
            String customerId,
            String customerName,
            String identityNumber
    ) {
        AgentEvalDataset.AgentInput input = new AgentEvalDataset.AgentInput(
                customerId,
                customerName,
                identityNumber,
                "INDIVIDUAL",
                "2026-08-12",
                "alert",
                "case"
        );
        AgentEvalDataset.RiskFacts riskFacts =
                new AgentEvalDataset.RiskFacts(0, 0, 0, true, true, 0, 0, false, 0);
        AgentEvalDataset.ToolFixture fixture = new AgentEvalDataset.ToolFixture(
                "TX-" + customerId + "-SECRET",
                "CORP-" + customerId + "-SECRET",
                "SANCTION-" + customerId + "-SECRET",
                "legal query",
                List.of("customer due diligence", "AML"),
                "LEGAL-" + customerId + "-SECRET",
                riskFacts
        );
        AgentEvalDataset.ExpectedOutcome expected = new AgentEvalDataset.ExpectedOutcome(
                "LOW",
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        AgentEvalDataset.Annotation annotation = new AgentEvalDataset.Annotation(
                "rationale", List.of(), "PENDING_DOMAIN_REVIEW", "review");
        return new AgentEvalDataset.AgentEvalCase(
                "CASE-" + customerId,
                "DEV",
                "SCENARIO",
                "EASY",
                input,
                fixture,
                expected,
                annotation
        );
    }
}
