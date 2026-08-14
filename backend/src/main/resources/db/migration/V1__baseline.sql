-- AML Agent 平台基线结构（MySQL 8）
-- 与 JPA 实体一一对应；生产环境 ddl-auto=validate + Flyway 管理 schema

CREATE TABLE aml_case (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id       VARCHAR(32)  NOT NULL,
    customer_name     VARCHAR(64),
    alert_rule        VARCHAR(255),
    status            VARCHAR(32)  NOT NULL,
    risk_level        VARCHAR(32),
    raw_risk_level    VARCHAR(32),
    report_json       TEXT,
    summary           VARCHAR(255),
    report_source     VARCHAR(32),
    snapshot_id       VARCHAR(64),
    execution_version INT          NOT NULL DEFAULT 0,
    locked_by         VARCHAR(64),
    locked_at         DATETIME(6),
    heartbeat_at      DATETIME(6),
    retry_count       INT          NOT NULL DEFAULT 0,
    next_retry_at     DATETIME(6),
    failure_code      VARCHAR(64),
    failure_message   VARCHAR(512),
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    KEY idx_case_status (status),
    KEY idx_case_customer (customer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE aml_case_log (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id    BIGINT      NOT NULL,
    stage      VARCHAR(32) NOT NULL,
    content    TEXT,
    created_at DATETIME(6) NOT NULL,
    KEY idx_case_log_case (case_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE case_execution (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id           BIGINT      NOT NULL,
    execution_version INT         NOT NULL,
    stage             VARCHAR(32) NOT NULL,
    input_digest      TEXT,
    output_json       TEXT,
    status            VARCHAR(32) NOT NULL,
    started_at        DATETIME(6) NOT NULL,
    completed_at      DATETIME(6),
    duration_ms       BIGINT,
    error_code        VARCHAR(64),
    error_message     VARCHAR(512),
    KEY idx_case_execution_case (case_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE outbox_event (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    aggregate_id      BIGINT      NOT NULL,
    event_type        VARCHAR(64) NOT NULL,
    execution_version INT         NOT NULL DEFAULT 0,
    idempotency_key   VARCHAR(160),
    payload           TEXT,
    status            VARCHAR(32) NOT NULL,
    retry_count       INT         NOT NULL DEFAULT 0,
    next_retry_at     DATETIME(6),
    created_at        DATETIME(6) NOT NULL,
    published_at      DATETIME(6),
    UNIQUE KEY uk_outbox_idempotency (idempotency_key),
    KEY idx_outbox_status_next (status, next_retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE manual_review (
    id                    BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id               BIGINT      NOT NULL,
    reviewer_id           VARCHAR(64),
    agent_risk_level      VARCHAR(32),
    guardrail_risk_level  VARCHAR(32),
    reviewer_risk_level   VARCHAR(32),
    decision              VARCHAR(32),
    comment               TEXT,
    created_at            DATETIME(6) NOT NULL,
    completed_at          DATETIME(6),
    KEY idx_manual_review_case (case_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE risk_rule (
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rule_code           VARCHAR(64)  NOT NULL,
    rule_name           VARCHAR(128),
    version             INT          NOT NULL DEFAULT 1,
    priority            INT          NOT NULL DEFAULT 100,
    condition_expression VARCHAR(255),
    target_risk_level   VARCHAR(32),
    action              VARCHAR(32)  DEFAULT 'AUTO_DONE',
    enabled             BIT(1)       NOT NULL DEFAULT b'1',
    effective_from      DATETIME(6),
    effective_to        DATETIME(6),
    description         VARCHAR(255),
    created_at          DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_risk_rule_code (rule_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE eval_report (
    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    eval_type   VARCHAR(32) NOT NULL,
    version_tag VARCHAR(64),
    metrics_json MEDIUMTEXT,
    created_at  DATETIME(6) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
