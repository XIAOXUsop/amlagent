package com.bank.aml.dto;

import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.datasource.entity.CaseLogEntity;

import java.time.LocalDateTime;

/**
 * 工作流日志 DTO。
 */
public record CaseLogDto(
        Long id,
        Long caseId,
        WorkflowStage stage,
        String content,
        LocalDateTime createdAt
) {
    public static CaseLogDto from(CaseLogEntity e) {
        return new CaseLogDto(e.getId(), e.getCaseId(), e.getStage(), e.getContent(), e.getCreatedAt());
    }
}
