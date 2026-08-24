package com.bank.aml.assistant.application;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantConversationStatus;
import com.bank.aml.assistant.domain.AssistantRunStatus;
import com.bank.aml.assistant.persistence.entity.AssistantConversationEntity;
import com.bank.aml.assistant.persistence.entity.AssistantMessageEntity;
import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import com.bank.aml.assistant.persistence.repository.AssistantConversationRepository;
import com.bank.aml.assistant.persistence.repository.AssistantMessageRepository;
import com.bank.aml.assistant.persistence.repository.AssistantRunRepository;
import com.bank.aml.common.exception.CustomerNotFoundException;
import com.bank.aml.datasource.entity.CustomerEntity;
import com.bank.aml.datasource.repository.CustomerRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 会话与消息事实源；不依赖模型，负责 owner、状态机、顺序号和幂等。 */
@Service
public class AssistantConversationService {
    private final AssistantProperties properties;
    private final CustomerRepository customers;
    private final AssistantConversationRepository conversations;
    private final AssistantMessageRepository messages;
    private final AssistantRunRepository runs;

    public AssistantConversationService(AssistantProperties properties, CustomerRepository customers,
                                        AssistantConversationRepository conversations,
                                        AssistantMessageRepository messages,
                                        AssistantRunRepository runs) {
        this.properties = properties;
        this.customers = customers;
        this.conversations = conversations;
        this.messages = messages;
        this.runs = runs;
    }

    @Transactional
    public AssistantConversationEntity create(Long customerId, String operatorUsername) {
        requireEnabled();
        String operator = requireOperator(operatorUsername);
        CustomerEntity customer = customers.findById(customerId)
                .filter(item -> !item.isDeleted())
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
        AssistantConversationEntity conversation = AssistantConversationEntity.create(
                operator, customer, LocalDateTime.now().plusDays(properties.getRetentionDays()));
        return conversations.save(conversation);
    }

    @Transactional(readOnly = true)
    public Page<AssistantConversationEntity> list(Long customerId, String operatorUsername, int page, int size) {
        requireEnabled();
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数无效");
        return conversations.findByOperatorUsernameAndCustomerIdOrderByUpdatedAtDesc(
                requireOperator(operatorUsername), customerId, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public AssistantConversationEntity get(String conversationId, String operatorUsername) {
        requireEnabled();
        return conversations.findByIdAndOperatorUsername(conversationId, requireOperator(operatorUsername))
                .orElseThrow(ConversationNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<AssistantMessageEntity> messages(String conversationId, String operatorUsername) {
        get(conversationId, operatorUsername);
        return messages.findTop100ByConversationIdOrderBySequenceNoAsc(conversationId);
    }

    @Transactional
    public void archive(String conversationId, String operatorUsername) {
        requireEnabled();
        AssistantConversationEntity conversation = conversations.findForUpdate(conversationId)
                .orElseThrow(ConversationNotFoundException::new);
        AssistantAuthorizationService.requireOwner(conversation, requireOperator(operatorUsername));
        if (conversation.getStatus() == AssistantConversationStatus.ACTIVE) {
            conversation.archive();
            conversations.save(conversation);
        }
    }

    /**
     * 原子接受用户消息并创建 AI 占位消息/run。同一会话持有数据库悲观锁，
     * 因而 sequenceNo 与 clientMessageId 在多实例下仍保持幂等。
     */
    @Transactional
    public AcceptedRun acceptMessage(String conversationId, String operatorUsername,
                                     String clientMessageId, String content) {
        requireEnabled();
        String operator = requireOperator(operatorUsername);
        String clientId = requireClientMessageId(clientMessageId);
        String message = requireMessage(content);

        AssistantConversationEntity conversation = conversations.findForUpdate(conversationId)
                .orElseThrow(ConversationNotFoundException::new);
        AssistantAuthorizationService.requireOwner(conversation, operator);
        requireActive(conversation);

        var existing = messages.findByConversationIdAndClientMessageId(conversationId, clientId);
        if (existing.isPresent()) {
            AssistantRunEntity run = runs.findByUserMessageId(existing.get().getId())
                    .orElseThrow(() -> new IllegalStateException("幂等消息缺少 run"));
            return new AcceptedRun(run.getId(), run.getUserMessageId(), run.getAssistantMessageId(), true);
        }
        if (runs.existsByConversationIdAndStatusIn(conversationId,
                List.of(AssistantRunStatus.ACCEPTED, AssistantRunStatus.PROCESSING))) {
            throw new ConversationBusyException();
        }

        long nextSequence = messages.findTopByConversationIdOrderBySequenceNoDesc(conversationId)
                .map(item -> item.getSequenceNo() + 1)
                .orElse(1L);
        AssistantMessageEntity user = messages.save(AssistantMessageEntity.user(
                conversationId, nextSequence, clientId, message));
        AssistantMessageEntity assistant = messages.save(AssistantMessageEntity.assistantPlaceholder(
                conversationId, nextSequence + 1));
        AssistantRunEntity run = runs.save(AssistantRunEntity.accepted(
                conversationId, user.getId(), assistant.getId()));
        conversation.touch();
        conversations.save(conversation);
        return new AcceptedRun(run.getId(), user.getId(), assistant.getId(), false);
    }

    private void requireActive(AssistantConversationEntity conversation) {
        if (conversation.getStatus() == AssistantConversationStatus.ARCHIVED) {
            throw new ConversationStateException("CONVERSATION_ARCHIVED", "会话已归档");
        }
        if (conversation.getStatus() == AssistantConversationStatus.EXPIRED
                || !conversation.getExpiresAt().isAfter(LocalDateTime.now())) {
            conversation.expire();
            conversations.save(conversation);
            throw new ConversationStateException("CONVERSATION_EXPIRED", "会话已过期");
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new AssistantDisabledException();
    }

    private String requireOperator(String operatorUsername) {
        if (operatorUsername == null || operatorUsername.isBlank()) {
            throw new ConversationNotFoundException();
        }
        return operatorUsername.trim();
    }

    private String requireClientMessageId(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 64 || !id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("clientMessageId 格式无效");
        }
        return id;
    }

    private String requireMessage(String value) {
        String message = value == null ? "" : value.trim();
        if (message.isEmpty()) throw new IllegalArgumentException("消息不能为空");
        if (message.length() > properties.getMaxMessageChars()) {
            throw new IllegalArgumentException("消息过长，最多 " + properties.getMaxMessageChars() + " 字");
        }
        return message;
    }

    public record AcceptedRun(String runId, String userMessageId, String assistantMessageId, boolean idempotentReplay) {}
}
