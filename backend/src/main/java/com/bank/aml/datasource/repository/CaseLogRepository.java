package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.CaseLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseLogRepository extends JpaRepository<CaseLogEntity, Long> {

    List<CaseLogEntity> findByCaseIdOrderByCreatedAtAsc(Long caseId);
}
