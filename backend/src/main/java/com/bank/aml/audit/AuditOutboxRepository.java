package com.bank.aml.audit;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditOutboxRepository extends JpaRepository<AuditOutboxEvent, Long> {

    boolean existsByEventKey(String eventKey);

    List<AuditOutboxEvent> findByStatusOrderByCreatedAtAsc(AuditOutboxEvent.Status status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM AuditOutboxEvent e WHERE e.id = :id")
    Optional<AuditOutboxEvent> findByIdForUpdate(@Param("id") Long id);

}
