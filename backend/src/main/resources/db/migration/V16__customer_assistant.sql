CREATE TABLE assistant_conversation (
    id                      VARCHAR(36)  NOT NULL PRIMARY KEY,
    operator_username       VARCHAR(128) NOT NULL,
    customer_id             BIGINT       NOT NULL,
    customer_no_at_creation VARCHAR(32)  NOT NULL,
    status                  VARCHAR(16)  NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    updated_at              DATETIME(6)  NOT NULL,
    expires_at              DATETIME(6)  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_assistant_conversation_customer FOREIGN KEY (customer_id) REFERENCES customer(id),
    INDEX idx_assistant_conversation_owner_customer (operator_username, customer_id, status, updated_at),
    INDEX idx_assistant_conversation_expiry (expires_at, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE assistant_message (
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    conversation_id    VARCHAR(36)  NOT NULL,
    sequence_no        BIGINT       NOT NULL,
    role               VARCHAR(16)  NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    result_type        VARCHAR(32),
    content_ciphertext MEDIUMTEXT   NOT NULL,
    content_digest     CHAR(64)     NOT NULL,
    client_message_id  VARCHAR(64),
    created_at         DATETIME(6)  NOT NULL,
    completed_at       DATETIME(6),
    CONSTRAINT fk_assistant_message_conversation FOREIGN KEY (conversation_id) REFERENCES assistant_conversation(id),
    UNIQUE KEY uk_assistant_message_sequence (conversation_id, sequence_no),
    UNIQUE KEY uk_assistant_message_client (conversation_id, client_message_id),
    INDEX idx_assistant_message_history (conversation_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE assistant_run (
    id                   VARCHAR(36)  NOT NULL PRIMARY KEY,
    conversation_id      VARCHAR(36)  NOT NULL,
    user_message_id      VARCHAR(36)  NOT NULL,
    assistant_message_id VARCHAR(36)  NOT NULL,
    snapshot_id          VARCHAR(64),
    status               VARCHAR(16)  NOT NULL,
    intent               VARCHAR(32),
    model_provider       VARCHAR(64),
    model_name           VARCHAR(128),
    prompt_version       VARCHAR(64),
    source_digest        CHAR(64),
    as_of_time           DATETIME(6),
    input_tokens         BIGINT,
    output_tokens        BIGINT,
    duration_ms          BIGINT,
    failure_code         VARCHAR(64),
    created_at           DATETIME(6)  NOT NULL,
    completed_at         DATETIME(6),
    CONSTRAINT fk_assistant_run_conversation FOREIGN KEY (conversation_id) REFERENCES assistant_conversation(id),
    CONSTRAINT fk_assistant_run_user_message FOREIGN KEY (user_message_id) REFERENCES assistant_message(id),
    CONSTRAINT fk_assistant_run_assistant_message FOREIGN KEY (assistant_message_id) REFERENCES assistant_message(id),
    UNIQUE KEY uk_assistant_run_user_message (user_message_id),
    INDEX idx_assistant_run_conversation_status (conversation_id, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE assistant_snapshot (
    snapshot_id             VARCHAR(64)  NOT NULL PRIMARY KEY,
    run_id                  VARCHAR(36)  NOT NULL,
    payload_ciphertext      MEDIUMTEXT   NOT NULL,
    source_digest           CHAR(64)     NOT NULL,
    source_system           VARCHAR(64)  NOT NULL,
    source_version          VARCHAR(128) NOT NULL,
    knowledge_index_version VARCHAR(128) NOT NULL,
    as_of_time              DATETIME(6)  NOT NULL,
    created_at              DATETIME(6)  NOT NULL,
    CONSTRAINT fk_assistant_snapshot_run FOREIGN KEY (run_id) REFERENCES assistant_run(id),
    UNIQUE KEY uk_assistant_snapshot_run (run_id),
    INDEX idx_assistant_snapshot_digest (source_digest)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE assistant_tool_trace (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id            VARCHAR(36)  NOT NULL,
    sequence_no       BIGINT       NOT NULL,
    tool_name         VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    duration_ms       BIGINT       NOT NULL,
    result_digest     CHAR(64),
    evidence_ids_json TEXT,
    error_code        VARCHAR(64),
    created_at        DATETIME(6)  NOT NULL,
    CONSTRAINT fk_assistant_tool_trace_run FOREIGN KEY (run_id) REFERENCES assistant_run(id),
    UNIQUE KEY uk_assistant_tool_trace_sequence (run_id, sequence_no),
    INDEX idx_assistant_tool_trace_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
