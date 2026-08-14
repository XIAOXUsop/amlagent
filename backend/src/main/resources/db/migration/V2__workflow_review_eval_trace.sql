-- V2：工作流人工复核版本、工具轨迹与冻结评测运行记录

-- 人工复核乐观锁版本
ALTER TABLE aml_case
    ADD COLUMN review_revision INT NOT NULL DEFAULT 0;

-- 人工复核审计字段 + 唯一键（caseId + reviewRevision）
ALTER TABLE manual_review
    ADD COLUMN review_revision INT NOT NULL DEFAULT 0,
    ADD COLUMN case_status_before VARCHAR(32),
    ADD COLUMN case_status_after VARCHAR(32),
    ADD UNIQUE KEY uk_review_case_revision (case_id, review_revision);

-- 生产工具调用轨迹
CREATE TABLE tool_execution_trace (
    id                BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id           BIGINT      NOT NULL,
    execution_version INT         NOT NULL,
    snapshot_id       VARCHAR(64) NOT NULL,
    sequence_no       BIGINT      NOT NULL,
    tool_name         VARCHAR(64) NOT NULL,
    requested         TINYINT(1)  NOT NULL,
    executed          TINYINT(1)  NOT NULL,
    success           TINYINT(1)  NOT NULL,
    argument_valid    TINYINT(1)  NOT NULL,
    duration_ms       BIGINT      NOT NULL,
    result_digest     VARCHAR(64),
    evidence_ids_json TEXT,
    error_code        VARCHAR(64),
    created_at        DATETIME(6) NOT NULL,
    KEY idx_tool_trace_case_version (case_id, execution_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 隐藏 TEST 冻结运行记录（freezeId 唯一，保证一次基线只正式执行一次）
CREATE TABLE eval_freeze_run (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    freeze_id      VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    run_id         VARCHAR(64),
    aggregate_json MEDIUMTEXT,
    started_at     DATETIME(6),
    completed_at   DATETIME(6),
    UNIQUE KEY uk_eval_freeze_id (freeze_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
