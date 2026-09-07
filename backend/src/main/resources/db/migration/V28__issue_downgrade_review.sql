-- 问题重要性降级双人确认（验收 A5-04）：
-- 分析员提交降级提案，另一位已认证 REVIEWER/ADMIN 在独立请求中确认；
-- 客户端传入的 confirmedBy 不再具有权威意义，确认身份由服务端从认证上下文写入。
-- 契约版本约定不变：0=存量兼容，1=调查契约 v1，2=解释核验政策；本迁移不改变契约版本。

CREATE TABLE explanation_issue_review (
    id                      BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id                 BIGINT        NOT NULL,
    issue_id                BIGINT        NOT NULL,
    proposal_revision       INT           NOT NULL,
    issue_revision          INT           NOT NULL,
    original_severity       VARCHAR(32)   NOT NULL,
    proposed_severity       VARCHAR(32)   NOT NULL,
    reason                  VARCHAR(1000) NOT NULL,
    evidence_reference      VARCHAR(160)  NULL,
    proposed_by             VARCHAR(64)   NOT NULL,
    proposed_at             DATETIME(6)   NOT NULL,
    status                  VARCHAR(24)   NOT NULL,
    confirmed_by            VARCHAR(64)   NULL,
    confirmed_at            DATETIME(6)   NULL,
    confirm_note            VARCHAR(500)  NULL,
    rejected_reason         VARCHAR(500)  NULL,
    KEY idx_issue_review_issue (issue_id),
    KEY idx_issue_review_case (case_id),
    KEY idx_issue_review_status (case_id, status),
    CONSTRAINT fk_issue_review_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
