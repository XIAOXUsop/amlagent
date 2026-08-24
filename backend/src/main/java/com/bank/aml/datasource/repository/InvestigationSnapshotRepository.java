package com.bank.aml.datasource.repository;

import com.bank.aml.datasource.entity.InvestigationSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InvestigationSnapshotRepository extends JpaRepository<InvestigationSnapshotEntity, String> {
    Optional<InvestigationSnapshotEntity> findByCaseIdAndExecutionVersion(Long caseId, int executionVersion);
}
