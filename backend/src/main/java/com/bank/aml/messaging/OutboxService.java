package com.bank.aml.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 写入服务：与工单状态变更同事务落库，由 {@link OutboxPublisher} 异步投递到 Redis Streams。
 * <p>通过幂等键 {@code caseId:eventType:executionVersion} 去重，保证同一执行版本同一事件只入队一次。
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository outboxRepository;

    public OutboxService(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void record(Long caseId, String eventType, int executionVersion) {
        String key = idempotencyKey(caseId, eventType, executionVersion);
        if (outboxRepository.existsByIdempotencyKey(key)) {
            log.debug("重复 Outbox 事件，忽略 key={}", key);
            return;
        }
        OutboxEvent event = new OutboxEvent();
        event.setAggregateId(caseId);
        event.setEventType(eventType);
        event.setExecutionVersion(executionVersion);
        event.setIdempotencyKey(key);
        event.setPayload("{\"caseId\":" + caseId
                + ",\"eventType\":\"" + eventType + "\""
                + ",\"executionVersion\":" + executionVersion + "}");
        outboxRepository.save(event);
    }

    /** 幂等键：caseId:eventType:executionVersion */
    public static String idempotencyKey(Long caseId, String eventType, int executionVersion) {
        return caseId + ":" + eventType + ":" + executionVersion;
    }
}
