package com.bank.aml.dto;

/** 稳定的死信队列消息响应契约。 */
public record DeadLetterDto(String streamId, long caseId, String eventType, int executionVersion,
        String idempotencyKey) {
}
