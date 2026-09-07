package com.bank.aml.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuditOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(AuditOutboxPublisher.class);
    private final AuditOutboxRepository repository;
    private final AuditOutboxDeliveryService delivery;

    public AuditOutboxPublisher(AuditOutboxRepository repository, AuditOutboxDeliveryService delivery) {
        this.repository = repository;
        this.delivery = delivery;
    }

    @Scheduled(fixedDelayString = "${aml.audit.outbox-poll-ms:2000}")
    public void publishPending() {
        repository.findByStatusOrderByCreatedAtAsc(
                        AuditOutboxEvent.Status.PENDING, PageRequest.of(0, 200))
                .forEach(event -> {
                    try {
                        delivery.deliver(event.getId());
                    } catch (RuntimeException failure) {
                        log.error("可靠审计投递失败 eventKey={}", event.getEventKey(), failure);
                    }
                });
    }
}
