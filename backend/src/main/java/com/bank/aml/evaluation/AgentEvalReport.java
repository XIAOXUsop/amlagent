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
        EfficiencyGate efficiency,
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
            "RISK_LEVEL_INVALID", "TRANSACTION_PROFILE_BLANK", "TRANSACTION_PROFILE_TOO_LONG",
            "CORPORATE_PROFILE_BLANK", "CORPORATE_PROFILE_TOO_LONG", "CONCLUSION_BLANK", "CONCLUSION_TOO_LONG",
            "SANCTIONS_NULL", "SANCTIONS_TOO_LARGE", "SANCTIONS_HAS_BLANK", "SANCTIONS_HAS_DUPLICATE",
            "SANCTIONS_ITEM_TOO_LONG", "LEGAL_BASIS_NULL", "LEGAL_BASIS_EMPTY", "LEGAL_BASIS_TOO_LARGE",
            "LEGAL_BASIS_HAS_BLANK", "LEGAL_BASIS_HAS_DUPLICATE", "LEGAL_BASIS_ITEM_TOO_LONG",
            "RISK_POINTS_NULL", "RISK_POINTS_EMPTY", "RISK_POINTS_TOO_LARGE", "RISK_POINTS_HAS_BLANK",
            "RISK_POINTS_HAS_DUPLICATE", "RISK_POINTS_ITEM_TOO_LONG", "EVIDENCE_CHAIN_NULL",
            "EVIDENCE_CHAIN_EMPTY", "EVIDENCE_CHAIN_TOO_LARGE", "EVIDENCE_CHAIN_HAS_BLANK",
            "EVIDENCE_CHAIN_HAS_DUPLICATE", "EVIDENCE_CHAIN_ITEM_TOO_LONG", "FINDING_CODES_NULL",
            "FINDING_CODES_EMPTY", "FINDING_CODES_TOO_LARGE", "FINDING_CODES_HAS_BLANK",
            "FINDING_CODES_HAS_DUPLICATE", "FINDING_CODES_ITEM_TOO_LONG", "FINDING_CODES_OUT_OF_VOCABULARY",
            "ACTION_CODES_NULL", "ACTION_CODES_EMPTY", "ACTION_CODES_TOO_LARGE", "ACTION_CODES_HAS_BLANK",
            "ACTION_CODES_HAS_DUPLICATE", "ACTION_CODES_ITEM_TOO_LONG", "ACTION_CODES_OUT_OF_VOCABULARY",
            "MANUAL_REVIEW_REQUIRED_NULL", "MANUAL_REVIEW_ACTION_INCONSISTENT", "IDENTITY_DATA_LEAKED",
            "EVIDENCE_ID_NOT_IN_SNAPSHOT", "LEGAL_EVIDENCE_ID_MISSING", "LEGAL_EVIDENCE_CHAIN_MISMATCH",
            "NO_SANCTION_HIT_UNSUPPORTED", "SANCTION_LEVEL_1_MATCH_UNSUPPORTED",
            "DOMESTIC_WATCHLIST_MATCH_UNSUPPORTED", "TRANSACTION_DATA_UNAVAILABLE_UNSUPPORTED",
            "UBO_UNVERIFIED_UNSUPPORTED", "UBO_DOCUMENTS_INCOMPLETE_UNSUPPORTED",
            "CROSS_BORDER_ACTIVITY_UNSUPPORTED", "HIGH_RISK_TRANSACTION_PATTERN_UNSUPPORTED",
            "FREEZE_ASSETS_UNSUPPORTED", "REPORT_TO_AUTHORITY_UNSUPPORTED",
            "FREEZE_ASSETS_LEGAL_SUPPORT_MISSING", "REPORT_TO_AUTHORITY_LEGAL_SUPPORT_MISSING",
            "STOP_SERVICE_LEGAL_SUPPORT_MISSING"
    );

    /** Copy suitable for persistence: keeps metrics and traces, removes model text and narrative/identity fields. */
    public AgentEvalReport withoutSensitiveDetails() {
        return new AgentEvalReport(
                runId, datasetId, datasetVersion, split, promptVersion, runtime, runStatus, omit(invalidReason),
                startedAt, durationMs, attempted, completed, scored, invalid, strictPassCount,
                strictPassRate, taskPassCount, taskPassRate, forbiddenClaimGatePolicy,
                schema, rawRisk, finalRisk, guardrails,
                rawEscalation, finalEscalation, findings, actions, citations, tools, forbiddenClaims,
                latency, tokens, efficiency,
                cases.stream().map(CaseResult::withoutSensitiveDetails).toList());
    }

    /** 仅聚合指标版本：隐藏 TEST 盲测不返回逐案例金标，避免针对测试集调优导致指标泄漏 */
    public AgentEvalReport aggregateOnly() {
        return new AgentEvalReport(
                runId, datasetId, datasetVersion, split, promptVersion, runtime, runStatus, omit(invalidReason),
                startedAt, durationMs, attempted, completed, scored, invalid, strictPassCount,
                strictPassRate, taskPassCount, taskPassRate, forbiddenClaimGatePolicy,
                schema, rawRisk, finalRisk, guardrails,
                rawEscalation, finalEscalation, findings, actions, citations, tools, forbiddenClaims,
                latency, tokens, efficiency, List.of());
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

    /** 预算门禁只用于暴露性能回归，不改变质量评分或把超预算样本伪装成失败案例。 */
    public record EfficiencyGate(
            long p95LatencyBudgetMs,
            long averageTokensPerCaseBudget,
            long observedP95LatencyMs,
            Long observedAverageTokensPerCase,
            Boolean latencyPass,
            Boolean tokenPass
    ) {
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
            List<String> requiredTools,
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
                    List.of(), List.of(), List.of(), safeToolNames(requiredTools),
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
        return values.stream()
                .filter(Objects::nonNull)
                // Validator 的冒号后可能含 evidenceId；持久化只保留闭集中的通用原因码。
                .map(value -> value.split(":", 2)[0])
                .filter(vocabulary::contains)
                .distinct()
                .toList();
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
