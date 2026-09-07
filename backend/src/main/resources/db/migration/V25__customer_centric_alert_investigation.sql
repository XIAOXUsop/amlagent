ALTER TABLE aml_case
    ADD COLUMN investigation_contract_version INT NOT NULL DEFAULT 0 AFTER review_revision;

CREATE TABLE aml_alert (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    external_alert_id  VARCHAR(64)  NOT NULL,
    customer_id        VARCHAR(32)  NOT NULL,
    rule_code          VARCHAR(64)  NOT NULL,
    scenario_code      VARCHAR(64)  NOT NULL,
    hit_reason         VARCHAR(500) NOT NULL,
    occurred_at        DATETIME(6)  NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    case_id            BIGINT       NULL,
    revision           INT          NOT NULL DEFAULT 0,
    resolution_reason  VARCHAR(500) NULL,
    created_by         VARCHAR(64)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_alert_external_id (external_alert_id),
    KEY idx_alert_status_occurred (status, occurred_at),
    KEY idx_alert_customer_status (customer_id, status),
    KEY idx_alert_case (case_id),
    CONSTRAINT fk_alert_case FOREIGN KEY (case_id) REFERENCES aml_case(id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE investigation_hypothesis (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id            BIGINT       NOT NULL,
    scenario_code      VARCHAR(64)  NOT NULL,
    hypothesis_code    VARCHAR(96)  NOT NULL,
    title              VARCHAR(160) NOT NULL,
    investigation_question VARCHAR(500) NOT NULL,
    required_evidence_types VARCHAR(500) NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    rationale          VARCHAR(2000) NULL,
    revision           INT          NOT NULL DEFAULT 0,
    created_by         VARCHAR(64)  NOT NULL,
    updated_by         VARCHAR(64)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_hypothesis_case_code (case_id, hypothesis_code),
    KEY idx_hypothesis_case_status (case_id, status),
    CONSTRAINT fk_hypothesis_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE investigation_evidence_link (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    hypothesis_id      BIGINT       NOT NULL,
    case_id            BIGINT       NOT NULL,
    evidence_type      VARCHAR(64)  NOT NULL,
    evidence_reference VARCHAR(160) NOT NULL,
    stance             VARCHAR(32)  NOT NULL,
    finding_summary    VARCHAR(1000) NOT NULL,
    created_by         VARCHAR(64)  NOT NULL,
    created_at         DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_hypothesis_evidence (hypothesis_id, evidence_type, evidence_reference),
    KEY idx_evidence_link_case (case_id),
    CONSTRAINT fk_evidence_hypothesis FOREIGN KEY (hypothesis_id) REFERENCES investigation_hypothesis(id),
    CONSTRAINT fk_evidence_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE alert_investigation_coverage (
    id                 BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    alert_id           BIGINT        NOT NULL,
    case_id            BIGINT        NOT NULL,
    hypothesis_id      BIGINT        NULL,
    conclusion         VARCHAR(32)   NOT NULL,
    analysis_summary   VARCHAR(1000) NULL,
    revision           INT           NOT NULL DEFAULT 0,
    updated_by         VARCHAR(64)   NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    UNIQUE KEY uk_alert_coverage (alert_id),
    KEY idx_coverage_case_conclusion (case_id, conclusion),
    CONSTRAINT fk_coverage_alert FOREIGN KEY (alert_id) REFERENCES aml_alert(id),
    CONSTRAINT fk_coverage_case FOREIGN KEY (case_id) REFERENCES aml_case(id),
    CONSTRAINT fk_coverage_hypothesis FOREIGN KEY (hypothesis_id) REFERENCES investigation_hypothesis(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 存量案件只用于兼容查询，不强制执行新调查契约；新创建案件由应用写 version=1。
INSERT INTO aml_alert (
    external_alert_id, customer_id, rule_code, scenario_code, hit_reason, occurred_at,
    status, case_id, revision, created_by, created_at, updated_at
)
SELECT CONCAT('LEGACY-CASE-', c.id), c.customer_id, 'LEGACY_IMPORT', 'PROFILE_MISMATCH',
       COALESCE(NULLIF(c.alert_rule, ''), '历史工单迁移预警'), c.created_at,
       'LINKED', c.id, 0, 'migration-v25', c.created_at, c.updated_at
FROM aml_case c;
