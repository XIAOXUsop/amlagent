-- 制裁名单候选人工核验记录：按客户 + 候选指纹保存追加式版本历史。
CREATE TABLE sanction_candidate_review (
    id                    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id           VARCHAR(32)  NOT NULL,
    candidate_fingerprint CHAR(64)     NOT NULL,
    candidate_name        VARCHAR(128) NOT NULL,
    list_type             VARCHAR(64)  NOT NULL,
    match_score           INT          NOT NULL,
    algorithm_decision    VARCHAR(32)  NOT NULL,
    review_decision       VARCHAR(32)  NOT NULL,
    reviewer_id           VARCHAR(64)  NOT NULL,
    comment_text          VARCHAR(500),
    review_revision       INT          NOT NULL,
    created_at            DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_sanction_review_revision (customer_id, candidate_fingerprint, review_revision),
    KEY idx_sanction_review_customer_created (customer_id, created_at),
    KEY idx_sanction_review_candidate (candidate_fingerprint, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
