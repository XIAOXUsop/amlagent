package com.bank.aml.dto;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;

import java.time.LocalDateTime;

/**
 * 工单响应 DTO（不直接暴露 JPA 实体）。
 */
public record CaseDto(
        Long id,
        String customerId,
        String customerName,
        String alertRule,
        CaseStatus status,
        String riskLevel,
        String reportJson,
        String summary,
        int executionVersion,
        int retryCount,
        String failureCode,
        String failureMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CaseDto from(CaseEntity e) {
        return new CaseDto(
                e.getId(), e.getCustomerId(), e.getCustomerName(), e.getAlertRule(),
                e.getStatus(), e.getRiskLevel(), e.getReportJson(), e.getSummary(),
                e.getExecutionVersion(), e.getRetryCount(), e.getFailureCode(), e.getFailureMessage(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
