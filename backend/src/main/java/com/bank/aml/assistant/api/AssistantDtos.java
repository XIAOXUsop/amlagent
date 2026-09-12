package com.bank.aml.assistant.api;

import com.bank.aml.assistant.application.AssistantConversationService;
import java.time.Instant;

public final class AssistantDtos {

    private AssistantDtos() {
    }

    public record ConversationResponse(String id, Long customerId, String customerNo, String status, Instant createdAt,
            Instant updatedAt, Instant expiresAt) {
        public static ConversationResponse from(AssistantConversationService.ConversationView view) {
            return new ConversationResponse(view.id(), view.customerId(), view.customerNo(), view.status(),
                    view.createdAt(), view.updatedAt(), view.expiresAt());
        }
    }

    public record MessageResponse(String id, long sequenceNo, String role, String status, String resultType,
            String content, Instant createdAt, Instant completedAt) {
        public static MessageResponse from(AssistantConversationService.MessageView view) {
            return new MessageResponse(view.id(), view.sequenceNo(), view.role(), view.status(), view.resultType(),
                    view.content(), view.createdAt(), view.completedAt());
        }
    }

    public record AcceptedRunResponse(String runId, String userMessageId, String assistantMessageId, String status,
            boolean idempotentReplay) {
    }

    public record StatusResponse(boolean enabled, int maxMessageChars) {
    }

}
