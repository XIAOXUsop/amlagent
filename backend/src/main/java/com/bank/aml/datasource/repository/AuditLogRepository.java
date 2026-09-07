package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
    boolean existsByEventKey(String eventKey);
    List<AuditLogEntity> findTop200ByOrderByOccurredAtDesc();
    List<AuditLogEntity> findTop100ByActionOrderByOccurredAtDesc(String action);
}
