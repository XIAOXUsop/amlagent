-- 将技术状态与人工业务处置拆开：DONE 既可能是确认可疑，也可能是合理排除。
ALTER TABLE aml_case
    ADD COLUMN review_disposition VARCHAR(48),
    ADD COLUMN review_reason_code VARCHAR(64),
    ADD COLUMN reviewed_at DATETIME(6),
    ADD KEY idx_case_review_disposition (review_disposition, reviewed_at);

-- 每次人工处置必须保存结构化原因码，自由文本 comment 记录具体分析过程。
ALTER TABLE manual_review
    MODIFY COLUMN decision VARCHAR(48),
    ADD COLUMN reason_code VARCHAR(64);
