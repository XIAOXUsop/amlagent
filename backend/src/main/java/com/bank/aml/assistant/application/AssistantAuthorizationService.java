package com.bank.aml.assistant.application;

import com.bank.aml.assistant.persistence.entity.AssistantConversationEntity;
import com.bank.aml.assistant.persistence.repository.AssistantConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistantAuthorizationService {
    private final AssistantConversationRepository conversations;

    public AssistantAuthorizationService(AssistantConversationRepository conversations) {
        this.conversations = conversations;
    }

    @Transactional(readOnly = true)
    public AssistantConversationEntity requireOwned(String conversationId, String operatorUsername) {
        return conversations.findByIdAndOperatorUsername(conversationId, operatorUsername)
                .orElseThrow(ConversationNotFoundException::new);
    }

    public static void requireOwner(AssistantConversationEntity conversation, String operatorUsername) {
        if (conversation == null || operatorUsername == null
                || !operatorUsername.equals(conversation.getOperatorUsername())) {
            throw new ConversationNotFoundException();
        }
    }
}
