package com.bank.aml.assistant.persistence.repository;

import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.time.LocalDateTime;
import com.bank.aml.assistant.domain.AssistantRunStatus;

public interface AssistantRunRepository extends JpaRepository<AssistantRunEntity, String> {
    Optional<AssistantRunEntity> findByUserMessageId(String userMessageId);
    Optional<AssistantRunEntity> findByIdAndConversationId(String id, String conversationId);
    boolean existsByConversationIdAndStatusIn(String conversationId, Collection<AssistantRunStatus> statuses);
    List<AssistantRunEntity> findTop100ByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
            Collection<AssistantRunStatus> statuses, LocalDateTime cutoff);
}
