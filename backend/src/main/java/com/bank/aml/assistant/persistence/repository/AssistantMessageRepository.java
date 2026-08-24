package com.bank.aml.assistant.persistence.repository;

import com.bank.aml.assistant.persistence.entity.AssistantMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessageEntity, String> {
    Optional<AssistantMessageEntity> findByConversationIdAndClientMessageId(String conversationId,
                                                                            String clientMessageId);
    Optional<AssistantMessageEntity> findTopByConversationIdOrderBySequenceNoDesc(String conversationId);
    List<AssistantMessageEntity> findTop100ByConversationIdOrderBySequenceNoAsc(String conversationId);
}
