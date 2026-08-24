-- V4：客户/人员管理表（新建预警工单可选客户的数据源）
CREATE TABLE customer (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_no  VARCHAR(32)  NOT NULL,
    name         VARCHAR(64)  NOT NULL,
    id_card      VARCHAR(64)  NOT NULL,
    type         VARCHAR(32),
    industry     VARCHAR(64),
    region       VARCHAR(64),
    reg_capital  VARCHAR(128),
    status       VARCHAR(16)  NOT NULL DEFAULT 'ENABLED',
    created_by   VARCHAR(64),
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    deleted      BIT(1)       NOT NULL DEFAULT b'0',
    UNIQUE KEY uk_customer_no (customer_no),
    UNIQUE KEY uk_customer_id_card (id_card)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
