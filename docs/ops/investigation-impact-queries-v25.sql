-- 影响清单查询（阶段一：V26 迁移前，schema 前提 = V25）
-- 依据：《代码审查问题修改计划 v1》第 7 节。全部为 SELECT，不含任何数据修改。
-- 适用时机：应用 V26__coverage_hypothesis_revision.sql 之前（此时 alert_investigation_coverage
-- 尚无 hypothesis_revision 列、investigation_snapshot 尚无 alerts_digest 列）。
-- 注意：本文件不能在 V26 之后重复用于同一目的；迁移后的版本绑定排查见
-- docs/ops/investigation-impact-queries-v26.sql。

-- 1. 版本 1 已自动 DONE 但没有最终人工决定的案件（问题 1 的受影响范围；
--    列名以 V19 迁移为准：人工决定时间为 reviewed_at，不存在 reported_at_alias）
SELECT c.id, c.customer_id, c.status, c.risk_level, c.raw_risk_level,
       c.investigation_contract_version, c.review_disposition, c.review_reason_code,
       c.reviewed_at, c.updated_at
FROM aml_case c
WHERE c.investigation_contract_version >= 1
  AND c.status = 'DONE'
  AND c.reviewed_at IS NULL;

-- 2. 仍有 OPEN 的补充尽调任务、但案件已离开可编辑状态（PENDING/HOLD）的案件
SELECT c.id AS case_id, c.status AS case_status, e.id AS edd_id, e.round_no, e.status AS edd_status,
       e.assigned_to, e.due_at
FROM aml_case c
JOIN enhanced_due_diligence_request e ON e.case_id = c.id
WHERE c.investigation_contract_version >= 1
  AND e.status = 'OPEN'
  AND c.status NOT IN ('PENDING', 'HOLD');

-- 3. 覆盖结论与其关联假设状态相互矛盾的案件（问题 3 的存量形态；不依赖 V26 新列）
SELECT cov.case_id, cov.alert_id, a.external_alert_id, cov.conclusion AS coverage_conclusion,
       h.status AS hypothesis_status
FROM alert_investigation_coverage cov
JOIN investigation_hypothesis h ON h.id = cov.hypothesis_id
LEFT JOIN aml_alert a ON a.id = cov.alert_id
WHERE cov.case_id IN (SELECT id FROM aml_case WHERE investigation_contract_version >= 1)
  AND cov.conclusion = 'SUSPICIOUS' AND h.status <> 'CONFIRMED'
UNION ALL
SELECT cov.case_id, cov.alert_id, a.external_alert_id, cov.conclusion, h.status
FROM alert_investigation_coverage cov
JOIN investigation_hypothesis h ON h.id = cov.hypothesis_id
LEFT JOIN aml_alert a ON a.id = cov.alert_id
WHERE cov.case_id IN (SELECT id FROM aml_case WHERE investigation_contract_version >= 1)
  AND cov.conclusion = 'EXPLAINED' AND h.status <> 'REJECTED';

-- 4. 已报送的可疑交易报告（修复时只能走既有退回补正机制，不得批量撤销）
SELECT r.case_id, r.status, r.external_reference, r.submitted_at, r.returned_at
FROM suspicious_transaction_report r
WHERE r.status = 'SUBMITTED';

-- 5. 关联预警数量超出单次尽调容量上限（默认 8）的活跃案件（A2 的存量形态；
--    这些案件一旦开始执行会因容量失败置为 FAILED，迁移前应先拆分）
--    ⚠ 运行前必须把 8 替换为部署实际 aml.agent.max-linked-alerts（本次编写时默认值 = 8；
--    覆盖 FAILED 在内的需恢复状态：如需包含 FAILED，请把 status 集合改为
--    ('PENDING', 'HOLD', 'FAILED') 并结合查询 4 的未完成清单使用）。
SELECT * FROM (
    SELECT c.id AS case_id, c.status,
           (SELECT COUNT(*) FROM aml_alert a
             WHERE a.case_id = c.id AND a.status = 'LINKED') AS linked_alerts
    FROM aml_case c
    WHERE c.investigation_contract_version >= 1
      AND c.status IN ('PENDING', 'HOLD')
) t
WHERE t.linked_alerts > 8;
