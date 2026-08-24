CREATE TABLE customer_transaction (
    id                    BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_no           VARCHAR(32)    NOT NULL,
    transacted_at         DATETIME(6)    NOT NULL,
    amount                DECIMAL(20, 2) NOT NULL,
    direction             VARCHAR(16)    NOT NULL,
    counterparty          VARCHAR(128)   NOT NULL,
    counterparty_region   VARCHAR(16)    NOT NULL,
    channel               VARCHAR(32),
    purpose               VARCHAR(128),
    currency              VARCHAR(8)     NOT NULL,
    source_updated_at     DATETIME(6)    NOT NULL,
    KEY idx_tx_customer_time (customer_no, transacted_at),
    CONSTRAINT fk_tx_customer_no FOREIGN KEY (customer_no) REFERENCES customer(customer_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE customer_shareholding (
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_no           VARCHAR(32)   NOT NULL,
    holder_name           VARCHAR(128)  NOT NULL,
    holder_type           VARCHAR(64)   NOT NULL,
    ownership_ratio       DECIMAL(8, 6) NOT NULL,
    ownership_level       VARCHAR(16)   NOT NULL,
    source_updated_at     DATETIME(6)   NOT NULL,
    KEY idx_shareholding_customer (customer_no, id),
    CONSTRAINT fk_shareholding_customer_no FOREIGN KEY (customer_no) REFERENCES customer(customer_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE sanction_entry (
    id                    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    subject_name          VARCHAR(128) NOT NULL,
    identity_number       VARCHAR(128),
    list_name             VARCHAR(64)  NOT NULL,
    reason                VARCHAR(512),
    severity              INT          NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    source_updated_at     DATETIME(6)  NOT NULL,
    KEY idx_sanction_name (subject_name),
    KEY idx_sanction_identity (identity_number),
    KEY idx_sanction_enabled_severity (enabled, severity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
