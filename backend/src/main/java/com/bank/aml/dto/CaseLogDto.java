package com.bank.aml.dto;

import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.datasource.entity.CaseLogEntity;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 工作流日志 DTO。
 */
public record CaseLogDto(Long id, Long caseId, WorkflowStage stage, String content, Instant createdAt) {
    public static CaseLogDto from(CaseLogEntity e) {
        return new CaseLogDto(e.getId(), e.getCaseId(), e.getStage(), e.getContent(),
                e.getCreatedAt() == null ? null : e.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
