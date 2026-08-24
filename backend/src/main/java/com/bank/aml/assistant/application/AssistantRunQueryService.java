package com.bank.aml.assistant.application;

import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import com.bank.aml.assistant.persistence.repository.AssistantRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssistantRunQueryService {
    private final AssistantRunRepository runs;
    private final AssistantConversationService conversations;

    public AssistantRunQueryService(AssistantRunRepository runs, AssistantConversationService conversations) {
        this.runs = runs;
        this.conversations = conversations;
    }

    @Transactional(readOnly = true)
    public AssistantRunEntity requireOwned(String runId, String operatorUsername) {
        AssistantRunEntity run = runs.findById(runId).orElseThrow(ConversationNotFoundException::new);
        conversations.get(run.getConversationId(), operatorUsername);
        return run;
    }
}
