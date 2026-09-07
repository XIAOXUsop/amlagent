ALTER TABLE audit_log
    ADD COLUMN event_key VARCHAR(160) NULL AFTER id,
    ADD UNIQUE KEY uk_audit_event_key (event_key);

CREATE TABLE audit_outbox (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_key    VARCHAR(160) NOT NULL,
    actor        VARCHAR(64)  NOT NULL,
    action_name  VARCHAR(64)  NOT NULL,
    target_type  VARCHAR(64)  NULL,
    target_id    VARCHAR(64)  NULL,
    outcome      VARCHAR(16)  NOT NULL,
    detail       VARCHAR(256) NULL,
    client_ip    VARCHAR(64)  NULL,
    status       VARCHAR(16)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    processed_at DATETIME(6)  NULL,
    UNIQUE KEY uk_audit_outbox_event (event_key),
    KEY idx_audit_outbox_status_created (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
