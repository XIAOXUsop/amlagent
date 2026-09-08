-- FR-03（v4 计划 §4.3）：义务必须绑定业务对象——需要核验的事实、关联金额/交易。
-- "提交有 followupRequired + 案件有任务"不足以视为覆盖；任务逐项承接义务。
-- obligation_fact_key：义务的事实键（如 DELIVERY:PO-2026-088、REFUND_AUTHORITY:T-1002）；
-- 空值为存量兼容（旧任务视为未绑定，新创建必须绑定）。
ALTER TABLE enhanced_due_diligence_request
    ADD COLUMN obligation_fact_key VARCHAR(96) NULL,
    ADD COLUMN obligation_amount DECIMAL(20, 2) NULL,
    ADD COLUMN obligation_transaction_ids VARCHAR(500) NULL;

CREATE INDEX idx_edd_obligation
    ON enhanced_due_diligence_request (case_id, obligation_fact_key, status);
