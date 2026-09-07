package com.bank.aml.operations;

import com.bank.aml.common.enums.CaseStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 可解释的案件运营视图；只由已有业务事实计算，不引入模型自由评分。 */
public record CaseOperationsView(
        Long caseId,
        String customerId,
        String customerName,
        CaseStatus caseStatus,
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
        boolean overdue,
        long minutesRemaining,
        String slaPolicy,
        LocalDateTime calculatedAt
) { }
