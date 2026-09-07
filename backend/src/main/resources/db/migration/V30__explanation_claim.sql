-- 代付业务核心（v3 计划 S3）：待验证事实 Claim 与事实-证据关联。
-- 契约版本约定不变：0=存量兼容，1=调查契约 v1，2=解释核验政策；本迁移不改变契约版本。

-- 待验证事实（C1~C6）：六问题与材料之间的桥梁；草稿可改，提交时冻结 claim revision 与采用状态。
CREATE TABLE explanation_claim (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id          BIGINT        NOT NULL,
    unit_id          BIGINT        NULL,
    claim_code       VARCHAR(8)    NOT NULL,
    status           VARCHAR(24)   NOT NULL,
    importance       VARCHAR(24)   NOT NULL DEFAULT 'DECISION_CRITICAL',
    subject_refs     VARCHAR(500)  NULL,
    transaction_ids  VARCHAR(500)  NULL,
    order_refs       VARCHAR(500)  NULL,
    judgement        VARCHAR(2000) NOT NULL,
    method_note      VARCHAR(1000) NULL,
    limitations      VARCHAR(1000) NULL,
    not_applicable_reason VARCHAR(1000) NULL,
    claim_revision   INT           NOT NULL DEFAULT 0,
    updated_by       VARCHAR(64)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    UNIQUE KEY uk_claim_case_unit_code (case_id, unit_id, claim_code),
    KEY idx_claim_case (case_id),
    CONSTRAINT fk_claim_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 事实-证据关联：claim → 材料版本/核验事件，支持或反对；同源家族标注；定位属于相应内容版本。
CREATE TABLE claim_evidence_link (
    id                  BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id             BIGINT        NOT NULL,
    claim_id            BIGINT        NOT NULL,
    artifact_version_id BIGINT        NULL,
    verification_event_id BIGINT      NULL,
    direction           VARCHAR(32)   NOT NULL,
    source_family       VARCHAR(96)   NULL,
    location            VARCHAR(255)  NULL,
    note                VARCHAR(500)  NULL,
    created_by          VARCHAR(64)   NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    KEY idx_link_claim (claim_id),
    KEY idx_link_artifact (artifact_version_id),
    CONSTRAINT fk_link_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
