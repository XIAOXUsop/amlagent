CREATE TABLE suspicious_transaction_report (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id             BIGINT       NOT NULL,
    review_id           BIGINT       NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    report_reason       TEXT         NOT NULL,
    created_by          VARCHAR(64)  NOT NULL,
    revision            INT          NOT NULL DEFAULT 0,
    external_reference  VARCHAR(128) NULL,
    submitted_by        VARCHAR(64)  NULL,
    submitted_at        DATETIME(6)  NULL,
    returned_by         VARCHAR(64)  NULL,
    returned_at         DATETIME(6)  NULL,
    return_reason       VARCHAR(500) NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_str_case (case_id),
    KEY idx_str_status_created (status, created_at),
    CONSTRAINT fk_str_case FOREIGN KEY (case_id) REFERENCES aml_case(id),
    CONSTRAINT fk_str_review FOREIGN KEY (review_id) REFERENCES manual_review(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 旧版本把“确认可疑”直接作为 DONE；升级后必须恢复为待报送，避免历史案件绕过报告闭环。
INSERT INTO suspicious_transaction_report (
    case_id, review_id, status, report_reason, created_by, revision, created_at, updated_at
)
SELECT c.id,
       r.id,
       'PENDING_SUBMISSION',
       r.comment,
       r.reviewer_id,
       0,
       COALESCE(r.completed_at, r.created_at, CURRENT_TIMESTAMP(6)),
       CURRENT_TIMESTAMP(6)
FROM aml_case c
JOIN manual_review r ON r.id = (
    SELECT MAX(mr.id)
    FROM manual_review mr
    WHERE mr.case_id = c.id
      AND mr.decision IN ('CONFIRM_SUSPICIOUS', 'APPROVE')
)
WHERE c.status = 'DONE'
  AND c.review_disposition = 'CONFIRM_SUSPICIOUS';

UPDATE aml_case c
JOIN suspicious_transaction_report r ON r.case_id = c.id
SET c.status = 'REPORT_PENDING'
WHERE c.status = 'DONE'
  AND r.status = 'PENDING_SUBMISSION';
