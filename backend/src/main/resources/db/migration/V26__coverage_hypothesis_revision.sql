-- 覆盖结论绑定形成决定时的假设版本：
--   NULL 表示“没有可证明的版本绑定”（存量数据），门禁会要求分析员重新确认，不批量回填历史版本。
--   非空值必须等于当前假设版本，否则视为“依据已变化”，阻断最终人工处置。
ALTER TABLE alert_investigation_coverage
    ADD COLUMN hypothesis_revision BIGINT NULL AFTER hypothesis_id;

-- 快照归档增加预警输入摘要：证明一次执行内模型/法规预检索/档案使用同一份冻结预警集合。
ALTER TABLE investigation_snapshot
    ADD COLUMN alerts_digest VARCHAR(64) NULL AFTER source_digest;

-- 门禁按案件批量读取覆盖与假设时的联合索引，避免逐条预警查库。
CREATE INDEX idx_coverage_case_hypothesis
    ON alert_investigation_coverage (case_id, hypothesis_id);
