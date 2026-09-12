package com.bank.aml.assistant.persistence.repository;

import com.bank.aml.assistant.domain.AssistantRunStatus;
import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssistantRunRepository extends JpaRepository<AssistantRunEntity, String> {

    Optional<AssistantRunEntity> findByUserMessageId(String userMessageId);

    Optional<AssistantRunEntity> findByIdAndConversationId(String id, String conversationId);

    boolean existsByConversationIdAndStatusIn(String conversationId, Collection<AssistantRunStatus> statuses);

    List<AssistantRunEntity> findTop100ByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
            Collection<AssistantRunStatus> statuses, LocalDateTime cutoff);

}
