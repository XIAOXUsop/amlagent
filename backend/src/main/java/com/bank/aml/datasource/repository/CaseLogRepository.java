package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.CaseLogEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseLogRepository extends JpaRepository<CaseLogEntity, Long> {

    List<CaseLogEntity> findByCaseIdOrderByCreatedAtAsc(Long caseId);

}
