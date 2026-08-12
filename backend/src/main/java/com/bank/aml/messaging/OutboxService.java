package com.bank.aml.messaging;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 写入服务：工单创建事务提交后调用，将尽调任务落为 Outbox 事件。
 */
@Service
public class OutboxService {

    private final OutboxRepository outboxRepository;

    public OutboxService(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public void record(Long caseId) {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateId(caseId);
        event.setEventType("CASE_CREATED");
        event.setPayload("{\"caseId\":" + caseId + "}");
        outboxRepository.save(event);
    }
}
