package com.bank.aml.messaging;

import com.bank.aml.messaging.OutboxEvent.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(OutboxStatus status, LocalDateTime now);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
