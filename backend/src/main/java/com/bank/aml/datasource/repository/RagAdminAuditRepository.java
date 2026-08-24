package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.RagAdminAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagAdminAuditRepository extends JpaRepository<RagAdminAuditEntity, Long> {
}
