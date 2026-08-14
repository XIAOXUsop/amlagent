package com.bank.aml.messaging;

/**
 * 工单入队事件类型：区分不同触发来源，配合 Outbox 幂等键 {@code caseId:eventType:executionVersion} 做精确去重。
 */
public enum WorkflowEventType {
    /** 工单创建（首次入队） */
    CASE_CREATED,
    /** 手动触发 / 人工重试 */
    CASE_MANUAL_TRIGGERED,
    /** 指数退避到期，自动重投 */
    CASE_RETRY_DUE,
    /** Worker 超时被接管，重新投递 */
    CASE_RECLAIMED,
    /** 死信重放 */
    CASE_DEAD_REPLAYED,
    /** 重试超限进死信 */
    CASE_DEAD_LETTER
}
