-- 补充尽调证据元数据：不保存附件正文，只保存来源、覆盖材料项与内容摘要。
CREATE TABLE enhanced_due_diligence_evidence (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id          BIGINT       NOT NULL,
    case_id             BIGINT       NOT NULL,
    required_item_code  VARCHAR(64)  NOT NULL,
    source_system       VARCHAR(64)  NOT NULL,
    source_reference    VARCHAR(128) NOT NULL,
    content_sha256      CHAR(64)     NOT NULL,
    captured_by         VARCHAR(64)  NOT NULL,
    captured_at         DATETIME(6)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_edd_evidence_source (request_id, source_system, source_reference),
    KEY idx_edd_evidence_request_item (request_id, required_item_code),
    KEY idx_edd_evidence_case (case_id),
    CONSTRAINT fk_edd_evidence_request FOREIGN KEY (request_id)
        REFERENCES enhanced_due_diligence_request(id),
    CONSTRAINT fk_edd_evidence_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
