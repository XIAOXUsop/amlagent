-- Expand-contract 的 contract 阶段。部署前必须先按
-- docs/guides/refund-ledger-integrity-rollout.md 完成 V35 兼容应用部署、存量检查与观察期。
-- 先扫描存量；发现任一不一致行即让迁移失败，不自动篡改金融事实。
CREATE TEMPORARY TABLE refund_integrity_migration_guard (
    marker TINYINT NOT NULL
);

INSERT INTO refund_integrity_migration_guard (marker)
SELECT NULL
FROM (
    SELECT refund_row.id
    FROM refund_event refund_row
    WHERE refund_row.amount <= 0
       OR refund_row.currency <> 'CNY'
       OR refund_row.event_status NOT IN ('REQUESTED', 'POSTED', 'REVERSED')
    UNION ALL
    SELECT allocation_row.id
    FROM refund_allocation allocation_row
    LEFT JOIN refund_event refund_row
      ON refund_row.id = allocation_row.refund_event_id
     AND refund_row.case_id = allocation_row.case_id
     AND refund_row.currency = allocation_row.currency
    WHERE allocation_row.allocated_amount <= 0
       OR allocation_row.currency <> 'CNY'
       OR refund_row.id IS NULL
    UNION ALL
    SELECT refund_row.id
    FROM refund_event refund_row
    LEFT JOIN refund_event reversed_row
      ON reversed_row.id = refund_row.reversed_event_id
     AND reversed_row.case_id = refund_row.case_id
     AND reversed_row.currency = refund_row.currency
    WHERE refund_row.reversed_event_id IS NOT NULL
      AND reversed_row.id IS NULL
) violations
LIMIT 1;

DROP TEMPORARY TABLE refund_integrity_migration_guard;

-- 退款金额账的关键不变量由数据库兜底，避免绕过应用服务后产生跨案件、跨币种或负金额分配。
ALTER TABLE refund_event
    ADD CONSTRAINT chk_refund_event_amount_positive CHECK (amount > 0),
    ADD CONSTRAINT chk_refund_event_currency CHECK (currency = 'CNY'),
    ADD CONSTRAINT chk_refund_event_status CHECK (event_status IN ('REQUESTED', 'POSTED', 'REVERSED')),
    ADD UNIQUE KEY uk_refund_event_identity (id, case_id, currency);

ALTER TABLE refund_event
    ADD CONSTRAINT fk_refund_event_reversal_identity
        FOREIGN KEY (reversed_event_id, case_id, currency)
        REFERENCES refund_event (id, case_id, currency);

ALTER TABLE refund_allocation
    DROP FOREIGN KEY fk_refund_alloc_event,
    ADD CONSTRAINT chk_refund_alloc_amount_positive CHECK (allocated_amount > 0),
    ADD CONSTRAINT chk_refund_alloc_currency CHECK (currency = 'CNY'),
    ADD CONSTRAINT fk_refund_alloc_event_identity
        FOREIGN KEY (refund_event_id, case_id, currency)
        REFERENCES refund_event (id, case_id, currency);
