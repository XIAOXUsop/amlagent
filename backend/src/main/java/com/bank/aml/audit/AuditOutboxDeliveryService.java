package com.bank.aml.audit;

import com.bank.aml.datasource.entity.AuditLogEntity;
import com.bank.aml.datasource.repository.AuditLogRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditOutboxDeliveryService {

    private final AuditOutboxRepository outbox;

    private final AuditLogRepository auditLog;

    private final Clock clock;

    public AuditOutboxDeliveryService(AuditOutboxRepository outbox, AuditLogRepository auditLog, Clock clock) {
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(Long id) {
        AuditOutboxEvent event = outbox.findByIdForUpdate(id).orElse(null);
        if (event == null || event.getStatus() != AuditOutboxEvent.Status.PENDING)
            return;
        if (!auditLog.existsByEventKey(event.getEventKey())) {
            AuditLogEntity record = new AuditLogEntity();
            record.setEventKey(event.getEventKey());
            record.setActor(event.getActor());
            record.setAction(event.getActionName());
            record.setTargetType(event.getTargetType());
            record.setTargetId(event.getTargetId());
            record.setOutcome(event.getOutcome());
            record.setDetail(event.getDetail());
            record.setClientIp(event.getClientIp());
            auditLog.save(record);
        }
        event.setStatus(AuditOutboxEvent.Status.PROCESSED);
        event.setProcessedAt(LocalDateTime.now(clock));
        outbox.save(event);
    }

}
