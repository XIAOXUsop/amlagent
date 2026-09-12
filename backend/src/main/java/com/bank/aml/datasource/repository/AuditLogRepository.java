package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.AuditLogEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    boolean existsByEventKey(String eventKey);

    List<AuditLogEntity> findTop200ByOrderByOccurredAtDesc();

    List<AuditLogEntity> findTop100ByActionOrderByOccurredAtDesc(String action);

}
