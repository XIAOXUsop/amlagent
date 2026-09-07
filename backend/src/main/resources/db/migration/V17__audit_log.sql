CREATE TABLE audit_log (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor       VARCHAR(64)  NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    target_type VARCHAR(64)  NULL,
    target_id   VARCHAR(64)  NULL,
    outcome     VARCHAR(16)  NOT NULL,
    detail      VARCHAR(256) NULL,
    client_ip   VARCHAR(64)  NULL,
    occurred_at DATETIME(6)  NOT NULL,
    INDEX idx_audit_action_time (action, occurred_at),
    INDEX idx_audit_actor_time (actor, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;