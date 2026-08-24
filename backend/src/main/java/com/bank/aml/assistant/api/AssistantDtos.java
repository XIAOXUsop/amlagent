package com.bank.aml.assistant.api;

import com.bank.aml.assistant.persistence.entity.AssistantConversationEntity;
import com.bank.aml.assistant.persistence.entity.AssistantMessageEntity;

import java.time.LocalDateTime;

public final class AssistantDtos {
    private AssistantDtos() {}

    public record ConversationResponse(String id, Long customerId, String customerNo, String status,
                                       LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime expiresAt) {
        public static ConversationResponse from(AssistantConversationEntity entity) {
            return new ConversationResponse(entity.getId(), entity.getCustomerId(), entity.getCustomerNoAtCreation(),
                    entity.getStatus().name(), entity.getCreatedAt(), entity.getUpdatedAt(), entity.getExpiresAt());
        }
    }

    public record MessageResponse(String id, long sequenceNo, String role, String status, String resultType,
                                  String content, LocalDateTime createdAt, LocalDateTime completedAt) {
        public static MessageResponse from(AssistantMessageEntity entity) {
            return new MessageResponse(entity.getId(), entity.getSequenceNo(), entity.getRole().name(),
                    entity.getStatus().name(), entity.getResultType() == null ? null : entity.getResultType().name(),
                    entity.content(), entity.getCreatedAt(), entity.getCompletedAt());
        }
    }

    public record AcceptedRunResponse(String runId, String userMessageId, String assistantMessageId,
                                      String status, boolean idempotentReplay) {}

    public record StatusResponse(boolean enabled, int maxMessageChars) {}
}
