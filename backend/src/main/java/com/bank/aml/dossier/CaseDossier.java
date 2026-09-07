package com.bank.aml.dossier;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.investigation.AlertCoverageConclusion;
import com.bank.aml.investigation.AlertStatus;
import com.bank.aml.investigation.EvidenceStance;
import com.bank.aml.investigation.HypothesisStatus;
import com.bank.aml.investigation.InvestigationEvidenceType;
import com.bank.aml.operations.CasePriority;
import com.bank.aml.operations.OperationPhase;
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
            List<EnhancedDueDiligenceRecord> enhancedDueDiligenceHistory,
            SuspiciousTransactionReportRecord suspiciousTransactionReport,
            List<SanctionReviewRecord> sanctionReviewHistory,
            List<AlertRecord> alerts,
            List<HypothesisRecord> hypotheses,
            List<InvestigationEvidenceRecord> investigationEvidence,
            List<AlertCoverageRecord> alertCoverage,
            CaseOperationsRecord operations
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
            int investigationContractVersion,
            String reviewDisposition,
            String reviewReasonCode,
            LocalDateTime reviewedAt,
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
            /** 本次执行冻结预警集合的 SHA-256；旧快照为 null。 */
            String alertsDigest,
            /** 本次执行实际见到的关联预警（编号 + 版本），与实时关联预警分开展示。 */
            List<FrozenAlertRef> frozenAlerts,
            LocalDateTime archivedAt
    ) {
    }

    /** 归档快照内冻结的单条预警引用；不能把今天数据库中的预警误标为旧执行实际见到的内容。 */
    public record FrozenAlertRef(
            Long alertId,
            String externalAlertId,
            int alertRevision
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
            String reasonCode,
            String comment,
            int reviewRevision,
            String caseStatusBefore,
            String caseStatusAfter,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
    }

    public record EnhancedDueDiligenceRecord(
            Long id,
            int roundNo,
            String reasonCode,
            List<String> requiredItems,
            String requestedBy,
            LocalDateTime requestedAt,
            String assignedTo,
            String assignedUnit,
            LocalDateTime dueAt,
            String status,
            int revision,
            String responseSummary,
            List<String> evidenceReferences,
            String respondedBy,
            LocalDateTime respondedAt,
            LocalDateTime resolvedAt,
            String cancelledBy,
            LocalDateTime cancelledAt,
            String cancellationReason,
            List<EnhancedDueDiligenceEvidenceRecord> evidenceItems
    ) {
    }

    public record EnhancedDueDiligenceEvidenceRecord(
            Long id,
            String evidenceId,
            String requiredItemCode,
            String sourceSystem,
            String sourceReference,
            String contentSha256,
            String capturedBy,
            LocalDateTime capturedAt
    ) {
    }

    public record SuspiciousTransactionReportRecord(
            Long id,
            Long reviewId,
            String status,
            String reportReason,
            String createdBy,
            int revision,
            String externalReference,
            String submittedBy,
            LocalDateTime submittedAt,
            String returnedBy,
            LocalDateTime returnedAt,
            String returnReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
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

    public record AlertRecord(
            Long id,
            String externalAlertId,
            String customerId,
            String ruleCode,
            String scenarioCode,
            String hitReason,
            LocalDateTime occurredAt,
            AlertStatus status,
            int revision,
            String resolutionReason,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) { }

    public record HypothesisRecord(
            Long id,
            String scenarioCode,
            String hypothesisCode,
            String title,
            String investigationQuestion,
            List<String> requiredEvidenceTypes,
            HypothesisStatus status,
            String rationale,
            int revision,
            String createdBy,
            String updatedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) { }

    public record InvestigationEvidenceRecord(
            Long id,
            Long hypothesisId,
            InvestigationEvidenceType evidenceType,
            String evidenceReference,
            EvidenceStance stance,
            String findingSummary,
            String createdBy,
            LocalDateTime createdAt
    ) { }

    public record AlertCoverageRecord(
            Long alertId,
            Long hypothesisId,
            AlertCoverageConclusion conclusion,
            String analysisSummary,
            int revision,
            String updatedBy,
            LocalDateTime updatedAt
    ) { }

    public record CaseOperationsRecord(
            CasePriority priority,
            int priorityScore,
            List<String> priorityReasons,
            String priorityPolicy,
            OperationPhase phase,
            String responsibleRole,
            String assignedTo,
            String assignedUnit,
            LocalDateTime clockStartedAt,
            LocalDateTime dueAt,
            String slaPolicy
    ) { }
}
