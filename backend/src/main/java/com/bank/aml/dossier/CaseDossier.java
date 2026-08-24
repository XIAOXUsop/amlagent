package com.bank.aml.dossier;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.tools.ToolExecutionTraceEntity;
import com.bank.aml.workflow.CaseExecution;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 可交付的案件调查档案。
 * <p>contentHash 是 content 的 SHA-256，便于归档系统或接收方校验内容是否被修改。
 */
public record CaseDossier(
        String schemaVersion,
        String classification,
        Instant generatedAt,
        String hashAlgorithm,
        String contentHash,
        Content content
) {
    public record Content(
            CaseSummary caseSummary,
            String reportParseStatus,
            JsonNode report,
            SnapshotMetadata snapshot,
            List<WorkflowLog> workflowLogs,
            List<ExecutionCheckpoint> executionCheckpoints,
            List<ToolTrace> toolTraces,
            List<ReviewRecord> reviewHistory,
            List<SanctionReviewRecord> sanctionReviewHistory
    ) {
    }

    public record CaseSummary(
            Long id,
            String customerId,
            String customerName,
            String alertRule,
            CaseStatus status,
            String rawRiskLevel,
            String finalRiskLevel,
            String summary,
            String reportSource,
            String modelProvider,
            String modelName,
            boolean modelFallback,
            int executionVersion,
            int reviewRevision,
            int retryCount,
            String failureCode,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    public record SnapshotMetadata(
            String snapshotId,
            int executionVersion,
            Instant asOfTime,
            String sourceSystem,
            String sourceVersion,
            String legalIndexVersion,
            String sourceDigest,
            LocalDateTime archivedAt
    ) {
    }

    public record WorkflowLog(Long id, WorkflowStage stage, String content, LocalDateTime createdAt) {
    }

    public record ExecutionCheckpoint(
            int executionVersion,
            WorkflowStage stage,
            CaseExecution.ExecutionStatus status,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            Long durationMs,
            String errorCode
    ) {
    }

    public record ToolTrace(
            int executionVersion,
            long sequenceNo,
            String toolName,
            boolean requested,
            boolean executed,
            boolean success,
            boolean argumentValid,
            long durationMs,
            String resultDigest,
            List<String> evidenceIds,
            String errorCode,
            LocalDateTime createdAt
    ) {
        public static ToolTrace from(ToolExecutionTraceEntity entity, List<String> evidenceIds) {
            return new ToolTrace(entity.getExecutionVersion(), entity.getSequenceNo(), entity.getToolName(),
                    entity.isRequested(), entity.isExecuted(), entity.isSuccess(), entity.isArgumentValid(),
                    entity.getDurationMs(), entity.getResultDigest(), evidenceIds, entity.getErrorCode(),
                    entity.getCreatedAt());
        }
    }

    public record ReviewRecord(
            Long id,
            String reviewerId,
            String agentRiskLevel,
            String guardrailRiskLevel,
            String reviewerRiskLevel,
            String decision,
            String comment,
            int reviewRevision,
            String caseStatusBefore,
            String caseStatusAfter,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
    }

    public record SanctionReviewRecord(
            String candidateFingerprint,
            String candidateName,
            String listType,
            int matchScore,
            String algorithmDecision,
            String reviewDecision,
            String reviewerId,
            String comment,
            int reviewRevision,
            LocalDateTime createdAt
    ) {
    }
}
