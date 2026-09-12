package com.bank.aml.assistant.persistence.repository;

import com.bank.aml.assistant.persistence.entity.AssistantSnapshotEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantSnapshotRepository extends JpaRepository<AssistantSnapshotEntity, String> {

    Optional<AssistantSnapshotEntity> findByRunId(String runId);

}
