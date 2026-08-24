package com.bank.aml.assistant.memory;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantMessageRole;
import com.bank.aml.assistant.domain.AssistantMessageStatus;
import com.bank.aml.assistant.persistence.entity.AssistantMessageEntity;
import com.bank.aml.assistant.persistence.repository.AssistantMessageRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 从持久化完整历史重建有界模型记忆；失败/阻断/进行中消息不进入模型。 */
@Component
public class AssistantHistoryLoader {
    private final AssistantMessageRepository messages;
    private final AssistantProperties properties;

    public AssistantHistoryLoader(AssistantMessageRepository messages, AssistantProperties properties) {
        this.messages = messages;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ChatMemory load(String conversationId, String currentUserMessageId) {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(properties.getHistoryMaxMessages());
        List<AssistantMessageEntity> history = messages.findTop100ByConversationIdOrderBySequenceNoAsc(conversationId);
        for (int index = 0; index + 1 < history.size(); index++) {
            AssistantMessageEntity user = history.get(index);
            AssistantMessageEntity answer = history.get(index + 1);
            if (user.getId().equals(currentUserMessageId)) break;
            if (user.getRole() == AssistantMessageRole.USER
                    && answer.getRole() == AssistantMessageRole.ASSISTANT
                    && answer.getStatus() == AssistantMessageStatus.COMPLETED) {
                memory.add(UserMessage.from(user.content()));
                memory.add(AiMessage.from(answer.content()));
                index++;
            }
        }
        return memory;
    }
}
