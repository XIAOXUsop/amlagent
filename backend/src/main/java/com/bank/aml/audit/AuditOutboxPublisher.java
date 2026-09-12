package com.bank.aml.audit;

import com.bank.aml.config.AuditProperties;
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

    private final AuditProperties properties;

    public AuditOutboxPublisher(AuditOutboxRepository repository, AuditOutboxDeliveryService delivery,
            AuditProperties properties) {
        this.repository = repository;
        this.delivery = delivery;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${aml.audit.outbox-poll-ms}")
    public void publishPending() {
        repository
            .findByStatusOrderByCreatedAtAsc(AuditOutboxEvent.Status.PENDING,
                    PageRequest.of(0, properties.getOutboxBatchSize()))
            .forEach(event -> {
                try {
                    delivery.deliver(event.getId());
                }
                catch (RuntimeException failure) {
                    log.error("可靠审计投递失败 eventKey={}", event.getEventKey(), failure);
                }
            });
    }

}
