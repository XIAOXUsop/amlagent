CREATE TABLE rag_admin_audit (
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor           VARCHAR(128) NOT NULL,
    action_name     VARCHAR(64) NOT NULL,
    target_version  VARCHAR(64) NULL,
    outcome         VARCHAR(32) NOT NULL,
    detail_code     VARCHAR(128) NULL,
    occurred_at     DATETIME(6) NOT NULL,
    INDEX idx_rag_admin_audit_occurred (occurred_at),
    INDEX idx_rag_admin_audit_actor_action (actor, action_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
