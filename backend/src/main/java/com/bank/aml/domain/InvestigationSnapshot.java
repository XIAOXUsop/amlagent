package com.bank.aml.domain;

import com.bank.aml.rag.LegalDoc;
import com.bank.aml.risk.RiskContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 不可变尽调快照：一次工单执行中，Agent 推理与 Guardrails 校验共享的同一份冻结业务事实。
 * <p>在 Agent 推理前由 {@code InvestigationSnapshotFactory} 从数据源一次性组装并冻结，
 * 包含交易、股权、制裁的原始领域对象、关联预警、预检索的法规证据与派生风险事实；
 * Agent 工具与 Guardrails 只读本快照，不再二次访问可变业务数据。
 * 报告可通过 {@code snapshotId} / {@code sourceDigest} / {@code alertsDigest} / {@code evidenceId}
 * 端到端追溯到数据来源与版本。
 *
 * @param snapshotSchemaVersion 快照结构版本：0=旧 schema（无预警事实），1=包含冻结预警集合。
 *                              旧归档缺少新增字段时反序列化为 0，加载与档案导出继续成功。
 */
public record InvestigationSnapshot(
        String snapshotId,
        Long caseId,
        int executionVersion,
        Instant asOfTime,
        CustomerProfile customer,
        List<InvestigationAlertSnapshot> alerts,
        List<TransactionRecord> transactions,
        List<ShareholdingRecord> shareholdings,
        List<SanctionRecord> sanctionHits,
        List<LegalDoc> legalEvidence,
        /** 按法规主题冻结的证据包；Agent 查询只能选择已授权主题，不再返回同一份混合结果。 */
        Map<String, List<LegalDoc>> legalEvidenceByTopic,
        /** 由冻结预警与风险事实解析出的法规查询关键词（与 legalEvidence 同源冻结，供工具校验查询覆盖） */
        List<String> legalKeywords,
        RiskContext riskFacts,
        String legalIndexVersion,
        /** 冻结业务事实摘要（客户/交易/股权/制裁口径，与历史快照保持一致，不改写其意义）。 */
        String sourceDigest,
        /** 冻结预警集合摘要：证明模型、法规预检索与档案使用同一份关联预警输入。 */
        String alertsDigest,
        Integer snapshotSchemaVersion
) {
    public InvestigationSnapshot {
        // 防御性拷贝：冻结集合不可被外部修改（Record 字段本身不可重新赋值，但 List 引用可变）
        alerts = alerts == null ? List.of() : List.copyOf(alerts);
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
        shareholdings = shareholdings == null ? List.of() : List.copyOf(shareholdings);
        sanctionHits = sanctionHits == null ? List.of() : List.copyOf(sanctionHits);
        legalEvidence = legalEvidence == null ? List.of() : List.copyOf(legalEvidence);
        if (legalEvidenceByTopic == null) {
            legalEvidenceByTopic = Map.of();
        } else {
            Map<String, List<LegalDoc>> frozen = new LinkedHashMap<>();
            legalEvidenceByTopic.forEach((topic, docs) -> frozen.put(topic, docs == null ? List.of() : List.copyOf(docs)));
            legalEvidenceByTopic = Map.copyOf(frozen);
        }
        legalKeywords = legalKeywords == null ? List.of() : List.copyOf(legalKeywords);
    }

    /** 旧 schema（不含预警事实）快照：存量归档与旧评测夹具继续使用，schemaVersion 记为 0。 */
    public InvestigationSnapshot(String snapshotId, Long caseId, int executionVersion, Instant asOfTime,
                                 CustomerProfile customer,
                                 List<TransactionRecord> transactions,
                                 List<ShareholdingRecord> shareholdings,
                                 List<SanctionRecord> sanctionHits,
                                 List<LegalDoc> legalEvidence,
                                 Map<String, List<LegalDoc>> legalEvidenceByTopic,
                                 List<String> legalKeywords,
                                 RiskContext riskFacts,
                                 String legalIndexVersion,
                                 String sourceDigest) {
        this(snapshotId, caseId, executionVersion, asOfTime, customer, List.of(), transactions, shareholdings,
                sanctionHits, legalEvidence, legalEvidenceByTopic, legalKeywords, riskFacts, legalIndexVersion,
                sourceDigest, null, SCHEMA_VERSION_LEGACY);
    }

    /** 旧 schema 版本：不含预警事实的存量快照。 */
    public static final int SCHEMA_VERSION_LEGACY = 0;
    /** 新 schema 版本：包含冻结关联预警集合与预警摘要。 */
    public static final int SCHEMA_VERSION_WITH_ALERTS = 1;

    /** 实际结构版本：反序列化旧归档时字段缺失即为 {@link #SCHEMA_VERSION_LEGACY}。 */
    public int schemaVersion() {
        return snapshotSchemaVersion == null ? SCHEMA_VERSION_LEGACY : snapshotSchemaVersion;
    }

    /** 快照是否冻结了真实关联预警（兼容路径的伪预警不算）。 */
    public boolean hasFrozenAlerts() {
        return schemaVersion() >= SCHEMA_VERSION_WITH_ALERTS
                && alerts.stream().anyMatch(alert -> alert.alertId() != null);
    }
}
