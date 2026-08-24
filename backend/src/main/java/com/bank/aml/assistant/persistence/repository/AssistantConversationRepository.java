package com.bank.aml.assistant.persistence.repository;

import com.bank.aml.assistant.domain.AssistantConversationStatus;
import com.bank.aml.assistant.persistence.entity.AssistantConversationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversationEntity, String> {
    Optional<AssistantConversationEntity> findByIdAndOperatorUsername(String id, String operatorUsername);
    List<AssistantConversationEntity> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            AssistantConversationStatus status, LocalDateTime cutoff);

    Page<AssistantConversationEntity> findByOperatorUsernameAndCustomerIdOrderByUpdatedAtDesc(
            String operatorUsername, Long customerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AssistantConversationEntity c where c.id = :id")
    Optional<AssistantConversationEntity> findForUpdate(@Param("id") String id);

    long countByStatus(AssistantConversationStatus status);
}
