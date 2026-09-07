-- 企业货款快进快出：合理解释核验专项（v2 计划 §12 数据模型收敛）
-- 原则：解释与核验落到逐预警单元；提交不可变；材料/核验追加版本；旧案语义不变。
-- 契约版本约定：0=存量兼容，1=调查契约 v1，2=解释核验政策（本迁移启用）；>=3 一律拒绝写入。

-- 案件事实序号：范围、材料有效性/核验、当前提交、关键问题处置、政策绑定及任务义务变更时推进。
ALTER TABLE aml_case
    ADD COLUMN case_facts_epoch BIGINT NOT NULL DEFAULT 0;

-- v2 覆盖绑定采用的单 元提交（最终校验必须存在且属于当前案件/预警/政策）。
ALTER TABLE alert_investigation_coverage
    ADD COLUMN unit_submission_id BIGINT NULL;

-- 核验依据：按案件追加版本冻结范围与事实摘要，不能被最新快照指针静默替换。
CREATE TABLE verification_basis (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id         BIGINT       NOT NULL,
    basis_revision  INT          NOT NULL,
    scope_json      TEXT         NOT NULL,
    source_cutoff   DATETIME(6)  NOT NULL,
    scope_digest    CHAR(64)     NOT NULL,
    basis_digest    CHAR(64)     NOT NULL,
    created_by      VARCHAR(64)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_basis_case_revision (case_id, basis_revision),
    KEY idx_basis_case (case_id),
    CONSTRAINT fk_basis_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 材料证据版本：正文不可更新；撤销/变更通过追加新版本与事件表达。
CREATE TABLE evidence_artifact_version (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id          BIGINT        NOT NULL,
    artifact_key     VARCHAR(96)   NOT NULL,
    version          INT           NOT NULL,
    source_system    VARCHAR(64)   NOT NULL,
    source_reference VARCHAR(160)  NOT NULL,
    content_location VARCHAR(255)  NULL,
    content_sha256   CHAR(64)      NOT NULL,
    claimed_sha256   CHAR(64)      NULL,
    availability     VARCHAR(24)   NOT NULL,
    integrity_status VARCHAR(24)   NOT NULL,
    period_from      DATETIME(6)   NULL,
    period_to        DATETIME(6)   NULL,
    source_chain     VARCHAR(500)  NULL,
    captured_by      VARCHAR(64)   NOT NULL,
    captured_at      DATETIME(6)   NOT NULL,
    UNIQUE KEY uk_artifact_case_key_version (case_id, artifact_key, version),
    KEY idx_artifact_case (case_id),
    CONSTRAINT fk_artifact_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 核验动作记录：技术解析与人工作用判断分开；追加不可变。
CREATE TABLE evidence_verification_event (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id             BIGINT        NOT NULL,
    artifact_version_id BIGINT        NOT NULL,
    method              VARCHAR(64)   NOT NULL,
    observed_facts      TEXT          NOT NULL,
    limitations         VARCHAR(1000) NULL,
    result              VARCHAR(32)   NOT NULL,
    actor               VARCHAR(64)   NOT NULL,
    previous_event_id   BIGINT        NULL,
    event_time          DATETIME(6)   NOT NULL,
    KEY idx_verification_artifact (artifact_version_id),
    KEY idx_verification_case (case_id),
    CONSTRAINT fk_verification_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 预警核验单元：一条有效预警一个单元；当前指针更新受案件锁保护。
CREATE TABLE alert_explanation_unit (
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id               BIGINT        NOT NULL,
    alert_id              BIGINT        NOT NULL,
    hypothesis_id         BIGINT        NULL,
    policy_code           VARCHAR(48)   NULL,
    scope_revision        INT           NOT NULL DEFAULT 0,
    draft_json            TEXT          NULL,
    draft_revision        INT           NOT NULL DEFAULT 0,
    editors               VARCHAR(1000) NULL,
    current_submission_id BIGINT        NULL,
    created_by            VARCHAR(64)   NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    UNIQUE KEY uk_unit_case_alert (case_id, alert_id),
    KEY idx_unit_case (case_id),
    CONSTRAINT fk_unit_case FOREIGN KEY (case_id) REFERENCES aml_case(id),
    CONSTRAINT fk_unit_alert FOREIGN KEY (alert_id) REFERENCES aml_alert(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 不可变单元提交：一次人工提交冻结问题、事实、材料、未知与建议。
CREATE TABLE explanation_submission (
    id                       BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    unit_id                  BIGINT        NOT NULL,
    case_id                  BIGINT        NOT NULL,
    submission_no            INT           NOT NULL,
    payload_json             TEXT          NOT NULL,
    outcome                  VARCHAR(24)   NOT NULL,
    suspicion_basis_complete BIT(1)       NOT NULL DEFAULT b'0',
    critical_unknown         BIT(1)       NOT NULL DEFAULT b'0',
    unresolved_disclosed     BIT(1)       NOT NULL DEFAULT b'0',
    followup_required        BIT(1)       NOT NULL DEFAULT b'0',
    basis_id                 BIGINT        NULL,
    input_digest             CHAR(64)      NOT NULL,
    idempotency_key          VARCHAR(96)   NULL,
    state                    VARCHAR(24)   NOT NULL,
    contributors             VARCHAR(1000) NULL,
    submitted_by             VARCHAR(64)   NOT NULL,
    submitted_at             DATETIME(6)   NOT NULL,
    superseded_reason        VARCHAR(255)  NULL,
    UNIQUE KEY uk_submission_unit_no (unit_id, submission_no),
    UNIQUE KEY uk_submission_idempotency (idempotency_key),
    KEY idx_submission_case_state (case_id, state),
    CONSTRAINT fk_submission_unit FOREIGN KEY (unit_id) REFERENCES alert_explanation_unit(id),
    CONSTRAINT fk_submission_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 材料在解释中的使用记录：用于受影响单元反查（材料变更 → 提交 STALE）。
CREATE TABLE explanation_evidence_use (
    id                     BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    submission_id          BIGINT        NOT NULL,
    case_id                BIGINT        NOT NULL,
    question_code          VARCHAR(8)    NOT NULL,
    artifact_version_id    BIGINT        NULL,
    verification_event_id  BIGINT        NULL,
    direction              VARCHAR(32)   NOT NULL,
    location               VARCHAR(255)  NULL,
    transaction_ids        VARCHAR(500)  NULL,
    note                   VARCHAR(500)  NULL,
    KEY idx_use_submission (submission_id),
    KEY idx_use_artifact (artifact_version_id),
    KEY idx_use_case (case_id),
    CONSTRAINT fk_use_submission FOREIGN KEY (submission_id) REFERENCES explanation_submission(id),
    CONSTRAINT fk_use_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 问题（差异/反证/缺口）登记：稳定 issueKey；待分派新材料挂案件级；不可静默删除。
CREATE TABLE explanation_issue (
    id                 BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id            BIGINT        NOT NULL,
    unit_id            BIGINT        NULL,
    issue_key          VARCHAR(96)   NOT NULL,
    severity           VARCHAR(32)   NOT NULL,
    question_code      VARCHAR(8)    NULL,
    transaction_ids    VARCHAR(500)  NULL,
    description        VARCHAR(1000) NOT NULL,
    disposition        VARCHAR(32)   NOT NULL,
    disposition_reason VARCHAR(1000) NULL,
    resolved_by        VARCHAR(64)   NULL,
    resolved_at        DATETIME(6)   NULL,
    confirmed_by       VARCHAR(64)   NULL,
    revision           INT           NOT NULL DEFAULT 0,
    created_by         VARCHAR(64)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    UNIQUE KEY uk_issue_case_key (case_id, issue_key),
    KEY idx_issue_case (case_id),
    CONSTRAINT fk_issue_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 补充尽调任务区分目的：当前判断补件 vs 决定后持续核验；接续可追溯到原任务/复核/问题。
ALTER TABLE enhanced_due_diligence_request
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'DECISION_SUPPORT',
    ADD COLUMN origin_request_id BIGINT NULL,
    ADD COLUMN origin_review_id BIGINT NULL,
    ADD COLUMN issue_bindings TEXT NULL,
    ADD COLUMN due_calendar_version VARCHAR(64) NULL,
    ADD COLUMN resolution_reason VARCHAR(500) NULL;

CREATE INDEX idx_edd_case_purpose_status
    ON enhanced_due_diligence_request (case_id, purpose, status);
