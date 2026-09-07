-- A5-07：接续任务完成标准完整保存（长度审计不等于保存）；
-- A5-06：完成/接替审计要求 resolvedBy 可追溯（字段已随 V24 schema 存在的补充）。
ALTER TABLE enhanced_due_diligence_request
    ADD COLUMN completion_standard TEXT NULL,
    ADD COLUMN resolved_by VARCHAR(64) NULL;
