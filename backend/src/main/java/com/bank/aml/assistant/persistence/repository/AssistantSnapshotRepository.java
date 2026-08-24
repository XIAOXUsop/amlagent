package com.bank.aml.assistant.persistence.repository;

import com.bank.aml.assistant.persistence.entity.AssistantSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssistantSnapshotRepository extends JpaRepository<AssistantSnapshotEntity, String> {
    Optional<AssistantSnapshotEntity> findByRunId(String runId);
}
