package com.bank.aml.domain;

import com.bank.aml.risk.RiskContext;

import java.time.Instant;
import java.util.List;

/**
 * 不可变尽调快照：一次工单执行中，Agent 推理与 Guardrails 校验共享的同一份数据事实。
 * <p>在 Agent 推理前由 {@code RiskFactAssembler} 从数据源一次性组装并冻结，
 * Guardrails 不再二次读取变化中的业务数据；报告可通过 {@code snapshotId} 与
 * {@code evidenceId} 端到端追溯到数据来源和版本。
 */
public record InvestigationSnapshot(
        String snapshotId,
        Long caseId,
        int executionVersion,
        Instant asOfTime,
        CustomerProfile customer,
        RiskContext riskFacts,
        List<SanctionRecord> sanctionHits
) {
}
