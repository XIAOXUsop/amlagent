CREATE TABLE rag_document_quarantine (
    id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_file   VARCHAR(255) NOT NULL,
    file_hash     CHAR(64) NOT NULL,
    reason_codes  VARCHAR(512) NOT NULL,
    detected_at   DATETIME(6) NOT NULL,
    UNIQUE KEY uk_rag_quarantine_file_hash (source_file, file_hash),
    INDEX idx_rag_quarantine_detected (detected_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
