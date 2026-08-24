package com.bank.aml.assistant.persistence.repository;

import com.bank.aml.assistant.persistence.entity.AssistantToolTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssistantToolTraceRepository extends JpaRepository<AssistantToolTraceEntity, Long> {
    List<AssistantToolTraceEntity> findByRunIdOrderBySequenceNoAsc(String runId);
}
