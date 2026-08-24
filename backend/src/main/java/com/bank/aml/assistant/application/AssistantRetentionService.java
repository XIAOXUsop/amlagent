package com.bank.aml.assistant.application;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantConversationStatus;
import com.bank.aml.assistant.persistence.repository.AssistantConversationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 保留期任务只做可审计的逻辑过期；V1 不在后台不可逆物理删除会话审计记录。 */
@Service
public class AssistantRetentionService {
    private final AssistantProperties properties;
    private final AssistantConversationRepository conversations;

    public AssistantRetentionService(AssistantProperties properties,
                                     AssistantConversationRepository conversations) {
        this.properties = properties;
        this.conversations = conversations;
    }

    @Scheduled(fixedDelayString = "${aml.assistant.retention-scan-ms:3600000}")
    @Transactional
    public int expireDueConversations() {
        if (!properties.isEnabled()) return 0;
        var due = conversations.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                AssistantConversationStatus.ACTIVE, LocalDateTime.now());
        due.forEach(item -> item.expire());
        conversations.saveAll(due);
        return due.size();
    }
}
