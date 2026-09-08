-- G1-1（v4 计划 §5.1 / RF-05）：服务器冻结预警命中范围。
-- 客户端 reviewedTransactionIds 只表达调查进度，不定义预警全集；
-- 缺少命中交易、来源无法枚举 → 范围缺口，禁止假定枚举完整。
-- trigger_transaction_ids：预警命中交易的 sourceRecordId JSON 数组（服务器冻结，人工可更正但须留版本）；
-- auxiliary_transaction_ids：辅助交易（上下文，不计入命中全集）；
-- scope_source_version：范围来源版本（监测批次/上游版本；变更可检测）。
ALTER TABLE aml_alert
    ADD COLUMN trigger_transaction_ids TEXT NULL,
    ADD COLUMN auxiliary_transaction_ids TEXT NULL,
    ADD COLUMN scope_source_version VARCHAR(64) NULL,
    ADD COLUMN scope_frozen_at DATETIME(6) NULL,
    ADD COLUMN scope_frozen_by VARCHAR(64) NULL;
