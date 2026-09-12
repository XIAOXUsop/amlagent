package com.bank.aml.assistant.application;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantConversationStatus;
import com.bank.aml.assistant.persistence.repository.AssistantConversationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保留期任务只做可审计的逻辑过期；V1 不在后台不可逆物理删除会话审计记录。 */
@Service
public class AssistantRetentionService {

    private final AssistantProperties properties;

    private final AssistantConversationRepository conversations;

    private final Clock clock;

    public AssistantRetentionService(AssistantProperties properties, AssistantConversationRepository conversations,
            Clock clock) {
        this.properties = properties;
        this.conversations = conversations;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${aml.assistant.retention-scan-ms}")
    @Transactional
    public int expireDueConversations() {
        if (!properties.isEnabled())
            return 0;
        var due = conversations.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                AssistantConversationStatus.ACTIVE, LocalDateTime.now(clock));
        due.forEach(item -> item.expire());
        conversations.saveAll(due);
        return due.size();
    }

}
