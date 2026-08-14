package com.bank.aml.domain;

import com.bank.aml.rag.LegalDoc;
import com.bank.aml.risk.RiskContext;

import java.time.Instant;
import java.util.List;

/**
 * 不可变尽调快照：一次工单执行中，Agent 推理与 Guardrails 校验共享的同一份冻结业务事实。
 * <p>在 Agent 推理前由 {@code InvestigationSnapshotFactory} 从数据源一次性组装并冻结，
 * 包含交易、股权、制裁的原始领域对象、预检索的法规证据与派生风险事实；Agent 工具与 Guardrails
 * 只读本快照，不再二次访问可变业务数据。报告可通过 {@code snapshotId} / {@code sourceDigest} /
 * {@code evidenceId} 端到端追溯到数据来源与版本。
 */
public record InvestigationSnapshot(
        String snapshotId,
        Long caseId,
        int executionVersion,
        Instant asOfTime,
        CustomerProfile customer,
        List<TransactionRecord> transactions,
        List<ShareholdingRecord> shareholdings,
        List<SanctionRecord> sanctionHits,
        List<LegalDoc> legalEvidence,
        RiskContext riskFacts,
        String legalIndexVersion,
        String sourceDigest
) {
    public InvestigationSnapshot {
        // 防御性拷贝：冻结集合不可被外部修改（Record 字段本身不可重新赋值，但 List 引用可变）
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
        shareholdings = shareholdings == null ? List.of() : List.copyOf(shareholdings);
        sanctionHits = sanctionHits == null ? List.of() : List.copyOf(sanctionHits);
        legalEvidence = legalEvidence == null ? List.of() : List.copyOf(legalEvidence);
    }
}
