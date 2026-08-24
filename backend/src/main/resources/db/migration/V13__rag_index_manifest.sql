ALTER TABLE legal_index_state
    ADD COLUMN previous_version VARCHAR(64) NULL AFTER active_version;

CREATE TABLE rag_index_manifest (
    index_version            CHAR(64) NOT NULL PRIMARY KEY,
    corpus_hash              CHAR(64) NOT NULL,
    chunker_version          VARCHAR(64) NOT NULL,
    metadata_schema_version  VARCHAR(64) NOT NULL,
    embedding_provider       VARCHAR(64) NOT NULL,
    embedding_model          VARCHAR(128) NOT NULL,
    embedding_revision       VARCHAR(128) NOT NULL,
    embedding_model_hash     VARCHAR(128) NOT NULL,
    embedding_dimensions     INT NOT NULL,
    distance_metric          VARCHAR(32) NOT NULL,
    status                   VARCHAR(32) NOT NULL,
    segment_count            INT NOT NULL DEFAULT 0,
    quality_report_json      TEXT NULL,
    failure_code             VARCHAR(128) NULL,
    created_at               DATETIME(6) NOT NULL,
    activated_at             DATETIME(6) NULL,
    retired_at               DATETIME(6) NULL,
    updated_at               DATETIME(6) NOT NULL,
    INDEX idx_rag_manifest_status_updated (status, updated_at),
    INDEX idx_rag_manifest_corpus_hash (corpus_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
