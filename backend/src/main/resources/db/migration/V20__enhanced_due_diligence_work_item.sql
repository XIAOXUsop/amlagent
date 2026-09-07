-- 补充尽调不是备注，而是可履行、可跟踪、可审计的业务任务。
CREATE TABLE enhanced_due_diligence_request (
    id                       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id                  BIGINT       NOT NULL,
    round_no                 INT          NOT NULL,
    reason_code              VARCHAR(64)  NOT NULL,
    required_items_json      TEXT         NOT NULL,
    requested_by             VARCHAR(64)  NOT NULL,
    requested_at             DATETIME(6)  NOT NULL,
    due_at                   DATETIME(6)  NOT NULL,
    status                   VARCHAR(32)  NOT NULL,
    revision                 INT          NOT NULL DEFAULT 0,
    response_summary         TEXT,
    evidence_references_json TEXT,
    responded_by             VARCHAR(64),
    responded_at             DATETIME(6),
    resolved_at              DATETIME(6),
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_edd_case_round (case_id, round_no),
    KEY idx_edd_case_status (case_id, status),
    KEY idx_edd_status_due (status, due_at),
    CONSTRAINT fk_edd_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
