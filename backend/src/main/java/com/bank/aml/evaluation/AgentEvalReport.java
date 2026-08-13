package com.bank.aml.evaluation;

import com.bank.aml.agent.DueDiligenceReport;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Versioned, machine-readable report produced by a real DEV Agent evaluation run. */
public record AgentEvalReport(
        String runId,
        String datasetId,
        String datasetVersion,
        String split,
        String promptVersion,
        RuntimeInfo runtime,
        String runStatus,
        String invalidReason,
        LocalDateTime startedAt,
        long durationMs,
        int attempted,
        int completed,
        int scored,
        int invalid,
        int strictPassCount,
        Rate strictPassRate,
        int taskPassCount,
        Rate taskPassRate,
        String forbiddenClaimGatePolicy,
        SchemaMetrics schema,
        RiskMetrics rawRisk,
        RiskMetrics finalRisk,
        GuardrailMetrics guardrails,
        BinaryMetrics rawEscalation,
        BinaryMetrics finalEscalation,
        CodeMetrics findings,
        CodeMetrics actions,
        CitationMetrics citations,
        ToolMetrics tools,
        ForbiddenMetrics forbiddenClaims,
        LatencyMetrics latency,
        TokenMetrics tokens,
        List<CaseResult> cases
) {

    private static final String OMITTED = "[OMITTED]";
    private static final String INVALID = "[INVALID]";
    private static final String UNKNOWN_TOOL = "[UNKNOWN_TOOL]";
    private static final Set<String> RISK_LEVELS = Set.of(
            "\u4f4e\u98ce\u9669", "\u4e2d\u98ce\u9669", "\u9ad8\u98ce\u9669"
    );
    private static final Set<String> TOOL_NAMES = Set.of(
            "transactionProfile", "corporateProfile", "checkSanctions", "searchLegal"
    );
    private static final Set<String> SCHEMA_VIOLATION_CODES = Set.of(
            "MODEL_ERROR", "OUTPUT_PARSE_ERROR", "SCHEMA_INVALID",
            "REPORT_NULL", "CUSTOMER_ID_MISMATCH", "CUSTOMER_NAME_MISMATCH",
            "RISK_LEVEL_INVALID", "TRANSACTION_PROFILE_EMPTY", "CORPORATE_PROFILE_EMPTY",
            "CONCLUSION_EMPTY", "LEGAL_BASIS_EMPTY", "EVIDENCE_CHAIN_EMPTY",
            "MANUAL_REVIEW_REQUIRED_MISSING", "FINDING_CODES_NULL", "ACTION_CODES_NULL",
            "FINDING_CODE_EMPTY", "ACTION_CODE_EMPTY", "FINDING_CODE_DUPLICATE",
            "ACTION_CODE_DUPLICATE", "FINDING_CODE_UNSUPPORTED", "ACTION_CODE_UNSUPPORTED"
    );

    /** Copy suitable for persistence: keeps metrics and traces, removes model text and narrative/identity fields. */
    public AgentEvalReport withoutSensitiveDetails() {
        return new AgentEvalReport(
                runId, datasetId, datasetVersion, split, promptVersion, runtime, runStatus, omit(invalidReason),
                startedAt, durationMs, attempted, completed, scored, invalid, strictPassCount,
                strictPassRate, taskPassCount, taskPassRate, forbiddenClaimGatePolicy,
                schema, rawRisk, finalRisk, guardrails,
                rawEscalation, finalEscalation, findings, actions, citations, tools, forbiddenClaims,
                latency, tokens,
                cases.stream().map(CaseResult::withoutSensitiveDetails).toList());
    }

    public record RuntimeInfo(
            String provider,
            String configuredModel,
            boolean realModel,
            boolean fallbackUsed
    ) {
    }

    /** All rates use an explicit numerator and denominator; value is null for an empty denominator. */
    public record Rate(long numerator, long denominator, Double value) {
    }

    public record SchemaMetrics(Rate successRate, Map<String, Long> violationCounts) {
    }

    public record RiskMetrics(
            Rate exactAccuracy,
            Rate highRiskRecall,
            Double macroF1,
            Double balancedAccuracy,
            Double ordinalMae,
            long underClassificationCount,
            long criticalMissCount,
            Map<String, Map<String, Long>> confusionMatrix
    ) {
    }

    public record BinaryMetrics(Rate accuracy, Rate precision, Rate recall, long falseNegativeCount) {
    }

    public record GuardrailMetrics(
            long upgradeCount,
            long falseUpgradeCount,
            long preventedCriticalMissCount,
            Map<String, Long> triggeredRuleCounts
    ) {
    }

    public record CodeMetrics(
            Rate microRecall,
            Rate microPrecision,
            Rate fullCoverageRate,
            long unsupportedCodeCount
    ) {
    }

    public record CitationMetrics(
            Rate evidenceIdRecall,
            Rate fullCoverageRate
    ) {
    }

    public record ToolMetrics(
            Rate requiredToolRecall,
            Rate callPrecision,
            Rate argumentAccuracy,
            Rate exactCoverageRate,
            long invalidArgumentCalls,
            long duplicateCalls,
            long executionFailures,
            double averageCallsPerCase
    ) {
    }

    public record ForbiddenMetrics(
            Rate complianceRate,
            Rate detectorCoverage,
            long violationCount,
            long unscorableCount
    ) {
    }

    public record LatencyMetrics(long p50Ms, long p95Ms) {
    }

    public record TokenMetrics(long inputTokens, long outputTokens, long totalTokens, int modelRequests) {
    }

    public record CaseResult(
            String caseId,
            String scenario,
            String status,
            String invalidReason,
            List<String> schemaViolations,
            String expectedRawRisk,
            String actualRawRisk,
            boolean rawRiskCorrect,
            String finalRisk,
            boolean finalRiskCorrect,
            boolean expectedEscalation,
            Boolean actualRawEscalation,
            boolean finalEscalation,
            List<String> triggeredGuardrailRules,
            List<String> requiredFindings,
            List<String> missingFindings,
            List<String> unsupportedFindings,
            List<String> requiredActions,
            List<String> missingActions,
            List<String> unsupportedActions,
            List<String> requiredEvidenceIds,
            List<String> missingEvidenceIds,
            List<String> expectedForbiddenClaims,
            List<AgentEvalToolCallTrace> toolCalls,
            List<String> missingTools,
            int invalidArgumentCalls,
            int duplicateCalls,
            List<ForbiddenCheck> forbiddenChecks,
            boolean endToEndTaskPass,
            boolean strictPass,
            long durationMs,
            AgentEvalModelObserver.Snapshot model,
            DueDiligenceReport report
    ) {
        public CaseResult withoutSensitiveDetails() {
            AgentEvalModelObserver.Snapshot safeModel = model == null ? null
                    : new AgentEvalModelObserver.Snapshot(
                    model.requestCount(), model.inputTokens(), model.outputTokens(), model.totalTokens(),
                    model.modelName(), null, omit(model.error()), safeRequestedTools(model.requestedTools()));
            DueDiligenceReport safeReport = report == null ? null : new DueDiligenceReport(
                    "[REDACTED]", "[REDACTED]", safeRisk(report.riskLevel()), OMITTED, OMITTED,
                    List.of(), List.of(), List.of(), OMITTED, List.of(),
                    report.manualReviewRequired(), safeCodes(report.findingCodes(), AgentEvalVocabulary.FINDING_CODES),
                    safeCodes(report.actionCodes(), AgentEvalVocabulary.ACTION_CODES));
            return new CaseResult(
                    caseId, scenario, status, omit(invalidReason), safeControlledCodes(schemaViolations, SCHEMA_VIOLATION_CODES), safeRisk(expectedRawRisk),
                    safeRisk(actualRawRisk), rawRiskCorrect, safeRisk(finalRisk), finalRiskCorrect, expectedEscalation,
                    actualRawEscalation, finalEscalation, triggeredGuardrailRules,
                    safeCodes(requiredFindings, AgentEvalVocabulary.FINDING_CODES),
                    safeCodes(missingFindings, AgentEvalVocabulary.FINDING_CODES),
                    safeCodes(unsupportedFindings, AgentEvalVocabulary.FINDING_CODES),
                    safeCodes(requiredActions, AgentEvalVocabulary.ACTION_CODES),
                    safeCodes(missingActions, AgentEvalVocabulary.ACTION_CODES),
                    safeCodes(unsupportedActions, AgentEvalVocabulary.ACTION_CODES),
                    List.of(), List.of(), List.of(),
                    safeToolCalls(toolCalls), safeToolNames(missingTools), invalidArgumentCalls, duplicateCalls,
                    safeForbiddenChecks(forbiddenChecks),
                    endToEndTaskPass, strictPass, durationMs, safeModel, safeReport);
        }
    }

    public record ForbiddenCheck(String claimCode, String status, String reason) {
    }

    private static String omit(String value) {
        return value == null ? null : OMITTED;
    }

    private static String safeRisk(String value) {
        if (value == null) {
            return null;
        }
        return RISK_LEVELS.contains(value) ? value : INVALID;
    }

    private static List<String> safeCodes(List<String> values, Set<String> vocabulary) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).filter(vocabulary::contains).toList();
    }

    private static List<String> safeControlledCodes(List<String> values, Set<String> vocabulary) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).filter(vocabulary::contains).toList();
    }

    private static List<String> safeToolNames(List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream().map(AgentEvalReport::safeToolName).toList();
    }

    private static String safeToolName(String name) {
        return name != null && TOOL_NAMES.contains(name) ? name : UNKNOWN_TOOL;
    }

    private static List<AgentEvalModelObserver.RequestedToolCall> safeRequestedTools(
            List<AgentEvalModelObserver.RequestedToolCall> requestedTools
    ) {
        if (requestedTools == null) {
            return List.of();
        }
        return requestedTools.stream()
                .filter(Objects::nonNull)
                .map(call -> new AgentEvalModelObserver.RequestedToolCall(
                        safeToolName(call.toolName())))
                .toList();
    }

    private static List<AgentEvalToolCallTrace> safeToolCalls(List<AgentEvalToolCallTrace> toolCalls) {
        if (toolCalls == null) {
            return List.of();
        }
        return toolCalls.stream()
                .filter(Objects::nonNull)
                .map(trace -> new AgentEvalToolCallTrace(
                        safeToolName(trace.toolName()), Map.of(), trace.success(), trace.argumentValid(),
                        trace.durationMs(), trace.resultDigest(), omit(trace.error())))
                .toList();
    }

    private static List<ForbiddenCheck> safeForbiddenChecks(List<ForbiddenCheck> checks) {
        if (checks == null) {
            return List.of();
        }
        return checks.stream()
                .filter(Objects::nonNull)
                .map(check -> new ForbiddenCheck(check.claimCode(), check.status(), OMITTED))
                .toList();
    }
}
