CREATE TABLE investigation_snapshot (
    snapshot_id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    case_id              BIGINT       NOT NULL,
    execution_version    INT          NOT NULL,
    as_of_time           DATETIME(6)  NOT NULL,
    source_system        VARCHAR(64)  NOT NULL,
    source_version       VARCHAR(128) NOT NULL,
    legal_index_version  VARCHAR(128) NOT NULL,
    source_digest        CHAR(64)     NOT NULL,
    payload_ciphertext   MEDIUMTEXT   NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    UNIQUE KEY uk_snapshot_case_version (case_id, execution_version),
    KEY idx_snapshot_digest (source_digest)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
