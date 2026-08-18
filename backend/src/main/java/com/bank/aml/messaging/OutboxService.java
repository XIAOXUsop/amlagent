package com.bank.aml.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outbox 写入服务：与工单状态变更同事务落库，由 {@link OutboxPublisher} 异步投递到 Redis Streams。
 * <p>通过幂等键 {@code caseId:eventType:executionVersion} 去重，保证同一执行版本同一事件只入队一次。
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
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
        event.setPayload(toJson(caseId, eventType, executionVersion));
        outboxRepository.save(event);
    }

    private String toJson(Long caseId, String eventType, int executionVersion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("eventType", eventType);
        payload.put("executionVersion", executionVersion);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // payload 仅留档，不参与投递语义；序列化失败不应阻断工单创建
            log.warn("Outbox payload 序列化失败（忽略）caseId={}", caseId, e);
            return "{}";
        }
    }

    /** 幂等键：caseId:eventType:executionVersion */
    public static String idempotencyKey(Long caseId, String eventType, int executionVersion) {
        return caseId + ":" + eventType + ":" + executionVersion;
    }
}
