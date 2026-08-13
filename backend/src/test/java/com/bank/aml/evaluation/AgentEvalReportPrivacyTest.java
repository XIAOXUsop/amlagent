package com.bank.aml.evaluation;

import com.bank.aml.agent.DueDiligenceReport;
import com.bank.aml.evaluation.AgentEvalReport.CaseResult;
import com.bank.aml.evaluation.AgentEvalReport.ForbiddenCheck;
import com.bank.aml.evaluation.AgentEvalReport.RuntimeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvalReportPrivacyTest {

    private static final String NAME_CANARY = "NAME_CANARY_ZHANG_SAN";
    private static final String ID_CANARY = "ID_CANARY_310101199001011234";
    private static final String QUERY_CANARY = "QUERY_CANARY_CUSTOMER_SEARCH";
    private static final String KEY_CANARY = "API_KEY_CANARY_VALUE_DO_NOT_PERSIST";
    private static final String ERROR_CANARY = "ERROR_CANARY_WITH_PRIVATE_CONTEXT";
    private static final String NARRATIVE_CANARY = "NARRATIVE_CANARY_MODEL_TEXT";
    private static final String UNKNOWN_TOOL_CANARY = "unknownToolCanary";
    private static final String ILLEGAL_FINDING_CANARY = "ILLEGAL_FINDING_CANARY";
    private static final String ILLEGAL_ACTION_CANARY = "ILLEGAL_ACTION_CANARY";

    @Test
    void persistenceCopyUsesClosedListsAndDoesNotSerializeSensitiveCanaries() throws Exception {
        AgentEvalToolCallTrace knownTrace = new AgentEvalToolCallTrace(
                "searchLegal",
                Map.of(
                        "customerName", NAME_CANARY,
                        "identityNumber", ID_CANARY,
                        "query", QUERY_CANARY,
                        "apiKey", KEY_CANARY,
                        "freeText", NARRATIVE_CANARY
                ),
                false, true, 17, "safe-result-digest", ERROR_CANARY
        );
        AgentEvalToolCallTrace unknownTrace = new AgentEvalToolCallTrace(
                UNKNOWN_TOOL_CANARY, Map.of("query", QUERY_CANARY), true, true,
                23, "safe-unknown-result-digest", null
        );
        AgentEvalModelObserver.Snapshot model = new AgentEvalModelObserver.Snapshot(
                2, 10, 20, 30, "safe-model", NARRATIVE_CANARY, ERROR_CANARY,
                List.of(
                        new AgentEvalModelObserver.RequestedToolCall("searchLegal"),
                        new AgentEvalModelObserver.RequestedToolCall(UNKNOWN_TOOL_CANARY)
                )
        );
        DueDiligenceReport modelReport = new DueDiligenceReport(
                ID_CANARY, NAME_CANARY, NARRATIVE_CANARY,
                NARRATIVE_CANARY, NARRATIVE_CANARY,
                List.of(NARRATIVE_CANARY), List.of(NARRATIVE_CANARY), List.of(NARRATIVE_CANARY),
                NARRATIVE_CANARY, List.of(NARRATIVE_CANARY), true,
                List.of("NO_SANCTION_HIT", ILLEGAL_FINDING_CANARY),
                List.of("MANUAL_REVIEW", ILLEGAL_ACTION_CANARY)
        );
        CaseResult unsafeCase = new CaseResult(
                "AE-PRIVACY", "privacy-regression", "SCHEMA_INVALID", ERROR_CANARY,
                List.of("RISK_LEVEL_INVALID"), "\u4f4e\u98ce\u9669", NARRATIVE_CANARY,
                false, NARRATIVE_CANARY, false, true, true, true,
                List.of("SANCTION_LEVEL_1"),
                List.of("NO_SANCTION_HIT", ILLEGAL_FINDING_CANARY),
                List.of(ILLEGAL_FINDING_CANARY), List.of(ILLEGAL_FINDING_CANARY),
                List.of("MANUAL_REVIEW", ILLEGAL_ACTION_CANARY),
                List.of(ILLEGAL_ACTION_CANARY), List.of(ILLEGAL_ACTION_CANARY),
                List.of("EV-1"), List.of(), List.of("FABRICATED_SANCTION_HIT"),
                List.of(knownTrace, unknownTrace), List.of(UNKNOWN_TOOL_CANARY),
                1, 0,
                List.of(new ForbiddenCheck("FABRICATED_SANCTION_HIT", "VIOLATION", NARRATIVE_CANARY)),
                false, false, 41, model, modelReport
        );
        AgentEvalReport unsafeReport = new AgentEvalReport(
                "run-privacy", "dataset", "1.1.0", "DEV", "prompt-v3",
                new RuntimeInfo("deepseek", "safe-model", true, false),
                "COMPLETED_WITH_ERRORS", ERROR_CANARY, LocalDateTime.of(2026, 8, 12, 12, 0),
                41, 1, 1, 0, 1, 0, null, 0, null,
                "UNSCORABLE_EXCLUDED", null, null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(unsafeCase)
        );

        AgentEvalReport safeReport = unsafeReport.withoutSensitiveDetails();
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(safeReport);

        assertThat(json).doesNotContain(
                NAME_CANARY, ID_CANARY, QUERY_CANARY, KEY_CANARY, ERROR_CANARY,
                NARRATIVE_CANARY, UNKNOWN_TOOL_CANARY, ILLEGAL_FINDING_CANARY, ILLEGAL_ACTION_CANARY
        );
        assertThat(safeReport.invalidReason()).isEqualTo("[OMITTED]");
        CaseResult safeCase = safeReport.cases().getFirst();
        assertThat(safeCase.invalidReason()).isEqualTo("[OMITTED]");
        assertThat(safeCase.actualRawRisk()).isEqualTo("[INVALID]");
        assertThat(safeCase.finalRisk()).isEqualTo("[INVALID]");
        assertThat(safeCase.report().riskLevel()).isEqualTo("[INVALID]");
        assertThat(safeCase.report().findingCodes()).containsExactly("NO_SANCTION_HIT");
        assertThat(safeCase.report().actionCodes()).containsExactly("MANUAL_REVIEW");
        assertThat(safeCase.requiredFindings()).containsExactly("NO_SANCTION_HIT");
        assertThat(safeCase.requiredActions()).containsExactly("MANUAL_REVIEW");
        assertThat(safeCase.requiredEvidenceIds()).isEmpty();
        assertThat(safeCase.missingEvidenceIds()).isEmpty();
        assertThat(safeCase.toolCalls()).allSatisfy(trace -> assertThat(trace.arguments()).isEmpty());
        assertThat(safeCase.toolCalls().getFirst().resultDigest()).isEqualTo("safe-result-digest");
        assertThat(safeCase.toolCalls().getFirst().durationMs()).isEqualTo(17);
        assertThat(safeCase.toolCalls().getFirst().error()).isEqualTo("[OMITTED]");
        assertThat(safeCase.toolCalls().get(1).toolName()).isEqualTo("[UNKNOWN_TOOL]");
        assertThat(safeCase.missingTools()).containsExactly("[UNKNOWN_TOOL]");
        assertThat(safeCase.model().requestedTools()).extracting(AgentEvalModelObserver.RequestedToolCall::toolName)
                .containsExactly("searchLegal", "[UNKNOWN_TOOL]");
        assertThat(safeCase.forbiddenChecks().getFirst().reason()).isEqualTo("[OMITTED]");
    }
}
