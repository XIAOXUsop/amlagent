-- 影响清单查询（阶段二：V26 迁移后，schema 前提 = V26）
-- 依据：《代码审查问题修改计划 v1》第 7 节。全部为 SELECT，不含任何数据修改。
-- 适用时机：应用 V26__coverage_hypothesis_revision.sql 之后。
-- 迁移前（V25）可执行的影响排查见 docs/ops/investigation-impact-queries-v25.sql。

-- 1. 覆盖缺少可证明的假设版本绑定（hypothesis_revision IS NULL）且已有结论的案件
--    （V26 迁移后，这些覆盖在新门禁下被阻断；带出案件状态以便分类：
--    PENDING/HOLD 等可编辑案件 → 详情页“重新确认”；DONE/REPORT_PENDING 等终态案件 →
--    只读，不引导到页面重新确认，见查询 5/7 的治理路径）
SELECT cov.case_id, c.status AS case_status, cov.alert_id, a.external_alert_id, cov.conclusion,
       cov.analysis_summary, cov.revision, cov.updated_by
FROM alert_investigation_coverage cov
JOIN aml_case c ON c.id = cov.case_id
LEFT JOIN aml_alert a ON a.id = cov.alert_id
WHERE c.investigation_contract_version >= 1
  AND cov.conclusion <> 'PENDING'
  AND cov.hypothesis_revision IS NULL;

-- 2. 覆盖绑定的假设版本已过期（改判后未重新确认）
--    （注意：必须关联 aml_alert 才能取到 external_alert_id；R2 修复点）
SELECT cov.case_id, cov.alert_id, a.external_alert_id,
       cov.hypothesis_revision AS bound_revision, h.revision AS current_revision,
       h.status AS hypothesis_status, cov.conclusion
FROM alert_investigation_coverage cov
JOIN investigation_hypothesis h ON h.id = cov.hypothesis_id
LEFT JOIN aml_alert a ON a.id = cov.alert_id
WHERE cov.case_id IN (SELECT id FROM aml_case WHERE investigation_contract_version >= 1)
  AND cov.hypothesis_revision IS NOT NULL
  AND cov.hypothesis_revision <> h.revision;

-- 3. 覆盖结论与其关联假设状态相互矛盾的案件（V26 后复核视图）
SELECT cov.case_id, cov.alert_id, a.external_alert_id, cov.conclusion AS coverage_conclusion,
       h.status AS hypothesis_status, cov.hypothesis_revision, h.revision AS current_revision
FROM alert_investigation_coverage cov
JOIN investigation_hypothesis h ON h.id = cov.hypothesis_id
LEFT JOIN aml_alert a ON a.id = cov.alert_id
WHERE cov.case_id IN (SELECT id FROM aml_case WHERE investigation_contract_version >= 1)
  AND ((cov.conclusion = 'SUSPICIOUS' AND h.status <> 'CONFIRMED')
    OR (cov.conclusion = 'EXPLAINED' AND h.status <> 'REJECTED'));

-- 4. 版本 1 仍未完成调查的活跃案件（按新门禁暴露的阻断项，由分析员补齐）
SELECT c.id AS case_id, c.status, c.risk_level,
       (SELECT COUNT(*) FROM aml_alert a
         WHERE a.case_id = c.id AND a.status = 'LINKED') AS linked_alerts,
       (SELECT COUNT(*) FROM alert_investigation_coverage cov
         WHERE cov.case_id = c.id AND cov.conclusion = 'PENDING') AS pending_coverages,
       (SELECT COUNT(*) FROM investigation_hypothesis h
         WHERE h.case_id = c.id AND h.status = 'OPEN') AS open_hypotheses
FROM aml_case c
WHERE c.investigation_contract_version >= 1
  AND c.status IN ('PENDING', 'RUNNING', 'HOLD', 'RETRY_WAIT', 'FAILED');

-- 5. 版本 1 已 DONE 但没有最终人工决定的案件（修复清单；不得复用 FAILED 重试接口）
SELECT c.id, c.customer_id, c.status, c.risk_level, c.raw_risk_level,
       c.review_disposition, c.review_reason_code, c.reviewed_at, c.snapshot_id
FROM aml_case c
WHERE c.investigation_contract_version >= 1
  AND c.status = 'DONE'
  AND c.reviewed_at IS NULL;

-- 6. 归档快照的预警摘要完整性（V26 后：新归档应带 alerts_digest，旧归档为 NULL 属预期）
SELECT s.snapshot_id, s.case_id, s.execution_version, s.alerts_digest, s.created_at
FROM investigation_snapshot s
WHERE s.case_id IN (SELECT id FROM aml_case WHERE investigation_contract_version >= 1)
ORDER BY s.case_id, s.execution_version;

-- 7. 修复操作说明（只读说明，不在此执行）
-- a) 查询 1/2 的未完成覆盖：由有权限的分析员在详情页对该预警执行“重新确认”
--    （显示当前假设依据、预填原分析、提交当前假设版本），不要求反转调查判断。
-- b) 查询 5 的已自动 DONE 案件：不得复用 FAILED 重试接口；如确需重新打开，必须通过
--    带案件版本/状态校验、理由登记与 Audit Outbox 的专用管理操作另行执行（本版本未提供该入口）。
-- c) 已报送案件：保留外部受理编号与原始调查事实，使用既有“退回补正”流程处理。
