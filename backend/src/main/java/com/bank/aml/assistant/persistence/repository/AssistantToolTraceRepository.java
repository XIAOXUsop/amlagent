package com.bank.aml.assistant.persistence.repository;

import com.bank.aml.assistant.persistence.entity.AssistantToolTraceEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantToolTraceRepository extends JpaRepository<AssistantToolTraceEntity, Long> {

    List<AssistantToolTraceEntity> findByRunIdOrderBySequenceNoAsc(String runId);

}
