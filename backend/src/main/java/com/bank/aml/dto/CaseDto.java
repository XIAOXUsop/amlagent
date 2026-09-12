package com.bank.aml.dto;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 工单响应 DTO（不直接暴露 JPA 实体）。
 */
public record CaseDto(Long id, String customerId, String customerName, String alertRule, CaseStatus status,
        String riskLevel, String rawRiskLevel, String reportJson, String summary, String reportSource,
        String snapshotId, String modelProvider, String modelName, boolean modelFallback, int executionVersion,
        int reviewRevision, int investigationContractVersion, String reviewDisposition, String reviewReasonCode,
        Instant reviewedAt, int retryCount, String failureCode, String failureMessage, Instant createdAt,
        Instant updatedAt) {
    public static CaseDto from(CaseEntity e) {
        return new CaseDto(e.getId(), e.getCustomerId(), e.getCustomerName(), e.getAlertRule(), e.getStatus(),
                e.getRiskLevel(), e.getRawRiskLevel(), e.getReportJson(), e.getSummary(), e.getReportSource(),
                e.getSnapshotId(), e.getModelProvider(), e.getModelName(), e.isModelFallback(), e.getExecutionVersion(),
                e.getReviewRevision(), e.getInvestigationContractVersion(), e.getReviewDisposition(),
                e.getReviewReasonCode(), toInstant(e.getReviewedAt()), e.getRetryCount(), e.getFailureCode(),
                publicFailureMessage(e.getFailureCode()), toInstant(e.getCreatedAt()), toInstant(e.getUpdatedAt()));
    }

    private static String publicFailureMessage(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "CLAIM_EXHAUSTED" -> "多次接管仍失败，请人工排查";
            case "RETRYABLE", "RETRY_EXHAUSTED" -> "工作流依赖暂时不可用，请稍后重试";
            case "NON_RETRYABLE" -> "工作流执行失败，请联系管理员";
            default -> "工作流执行异常，请稍后重试";
        };
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
