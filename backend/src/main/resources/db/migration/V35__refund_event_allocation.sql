-- G2-1（v4 计划 §6.1/§6.2 / RF-11/13/14/17/21/22）：退款事件与分配。
-- 金额账口径（§6.2，CNY 分为最小单位、定点十进制）：
--   原分配付款额 = 已核实退款额 + 当前仍保留的付款额；
--   一笔退款事件的分配合计 <= 该退款事件金额；
--   同一原付款分配的累计有效退款额 <= 原付款分配金额；
--   "申请退款"不提前减少实际资金余额；冲正恢复余额并保留历史。

CREATE TABLE refund_event (
    id                    BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id               BIGINT        NOT NULL,
    source_system         VARCHAR(64)   NOT NULL,
    external_event_id     VARCHAR(96)   NOT NULL,
    /** REQUESTED / POSTED / REVERSED（事实状态；调查处置分离）。 */
    event_status          VARCHAR(24)   NOT NULL,
    payer_subject         VARCHAR(128)  NOT NULL,
    payee_subject         VARCHAR(128)  NOT NULL,
    payee_account_ref     VARCHAR(96)   NULL,
    amount                DECIMAL(20,2) NOT NULL,
    currency              VARCHAR(8)    NOT NULL,
    effective_at          DATETIME(6)   NOT NULL,
    recorded_at           DATETIME(6)   NOT NULL,
    /** 事件内容摘要（同键同内容幂等判定；同键不同内容 → 更正冲突）。 */
    payload_digest        CHAR(64)      NOT NULL,
    /** REVERSED 事件指向的原入账事件。 */
    reversed_event_id     BIGINT        NULL,
    created_by            VARCHAR(64)   NOT NULL,
    created_at            DATETIME(6)   NOT NULL,
    UNIQUE KEY uk_refund_event_idempotency (case_id, source_system, external_event_id),
    KEY idx_refund_event_case (case_id),
    CONSTRAINT fk_refund_event_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE refund_allocation (
    id                        BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    case_id                   BIGINT        NOT NULL,
    refund_event_id           BIGINT        NOT NULL,
    original_transaction_id   VARCHAR(64)   NOT NULL,
    original_allocation_key   VARCHAR(96)   NOT NULL,
    allocated_amount          DECIMAL(20,2) NOT NULL,
    currency                  VARCHAR(8)    NOT NULL,
    returned_obligation_ref   VARCHAR(96)   NULL,
    created_by                VARCHAR(64)   NOT NULL,
    created_at                DATETIME(6)   NOT NULL,
    KEY idx_refund_alloc_event (refund_event_id),
    KEY idx_refund_alloc_case (case_id, original_transaction_id),
    CONSTRAINT fk_refund_alloc_event FOREIGN KEY (refund_event_id) REFERENCES refund_event(id),
    CONSTRAINT fk_refund_alloc_case FOREIGN KEY (case_id) REFERENCES aml_case(id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
