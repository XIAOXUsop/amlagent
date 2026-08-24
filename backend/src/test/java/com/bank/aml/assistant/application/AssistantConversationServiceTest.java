package com.bank.aml.assistant.application;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.persistence.entity.AssistantConversationEntity;
import com.bank.aml.assistant.persistence.entity.AssistantMessageEntity;
import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import com.bank.aml.assistant.persistence.repository.AssistantConversationRepository;
import com.bank.aml.assistant.persistence.repository.AssistantMessageRepository;
import com.bank.aml.assistant.persistence.repository.AssistantRunRepository;
import com.bank.aml.datasource.entity.CustomerEntity;
import com.bank.aml.datasource.repository.CustomerRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantConversationServiceTest {
    private final AssistantProperties properties = enabledProperties();
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final AssistantConversationRepository conversations = mock(AssistantConversationRepository.class);
    private final AssistantMessageRepository messages = mock(AssistantMessageRepository.class);
    private final AssistantRunRepository runs = mock(AssistantRunRepository.class);
    private final AssistantConversationService service = new AssistantConversationService(
            properties, customers, conversations, messages, runs);

    @Test
    void featureFlagFailsClosedBeforeDataAccess() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.create(1L, "admin"))
                .isInstanceOf(AssistantDisabledException.class);
        verify(customers, never()).findById(any());
    }

    @Test
    void createsConversationBoundToAuthenticatedOperatorAndCustomer() {
        CustomerEntity customer = customer(7L, "C-007");
        when(customers.findById(7L)).thenReturn(Optional.of(customer));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AssistantConversationEntity result = service.create(7L, " admin ");

        assertThat(result.getOperatorUsername()).isEqualTo("admin");
        assertThat(result.getCustomerId()).isEqualTo(7L);
        assertThat(result.getCustomerNoAtCreation()).isEqualTo("C-007");
    }

    @Test
    void ownerMismatchIsIndistinguishableFromMissingConversation() {
        AssistantConversationEntity conversation = conversation("owner");
        when(conversations.findForUpdate(conversation.getId())).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> service.acceptMessage(conversation.getId(), "attacker", "m-1", "test"))
                .isInstanceOf(ConversationNotFoundException.class);
        verify(messages, never()).save(any());
    }

    @Test
    void createsEncryptedMessagePairAndRunWithMonotonicSequence() {
        AssistantConversationEntity conversation = conversation("admin");
        when(conversations.findForUpdate(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdAndClientMessageId(conversation.getId(), "m-1"))
                .thenReturn(Optional.empty());
        AssistantMessageEntity previous = AssistantMessageEntity.user(conversation.getId(), 4, "old", "old");
        when(messages.findTopByConversationIdOrderBySequenceNoDesc(conversation.getId()))
                .thenReturn(Optional.of(previous));
        when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var accepted = service.acceptMessage(conversation.getId(), "admin", "m-1", "分析当前客户");

        assertThat(accepted.idempotentReplay()).isFalse();
        assertThat(accepted.runId()).isNotBlank();
        org.mockito.ArgumentCaptor<AssistantMessageEntity> captor = org.mockito.ArgumentCaptor.forClass(AssistantMessageEntity.class);
        verify(messages, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(AssistantMessageEntity::getSequenceNo).containsExactly(5L, 6L);
        assertThat(captor.getAllValues().getFirst().getContentCiphertext()).doesNotContain("分析当前客户");
        assertThat(captor.getAllValues().getFirst().content()).isEqualTo("分析当前客户");
    }

    @Test
    void replayReturnsExistingRunWithoutCreatingNewMessages() {
        AssistantConversationEntity conversation = conversation("admin");
        AssistantMessageEntity user = AssistantMessageEntity.user(conversation.getId(), 1, "m-1", "question");
        AssistantMessageEntity answer = AssistantMessageEntity.assistantPlaceholder(conversation.getId(), 2);
        AssistantRunEntity run = AssistantRunEntity.accepted(conversation.getId(), user.getId(), answer.getId());
        when(conversations.findForUpdate(conversation.getId())).thenReturn(Optional.of(conversation));
        when(messages.findByConversationIdAndClientMessageId(conversation.getId(), "m-1"))
                .thenReturn(Optional.of(user));
        when(runs.findByUserMessageId(user.getId())).thenReturn(Optional.of(run));

        var replay = service.acceptMessage(conversation.getId(), "admin", "m-1", "different body ignored");

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.runId()).isEqualTo(run.getId());
        verify(messages, never()).save(any());
    }

    @Test
    void archivedConversationRejectsNewMessage() {
        AssistantConversationEntity conversation = conversation("admin");
        conversation.archive();
        when(conversations.findForUpdate(conversation.getId())).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> service.acceptMessage(conversation.getId(), "admin", "m-1", "question"))
                .isInstanceOf(ConversationStateException.class)
                .extracting("code").isEqualTo("CONVERSATION_ARCHIVED");
    }

    private static AssistantProperties enabledProperties() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        return properties;
    }

    private AssistantConversationEntity conversation(String owner) {
        return AssistantConversationEntity.create(owner, customer(7L, "C-007"), LocalDateTime.now().plusDays(1));
    }

    private CustomerEntity customer(Long id, String customerNo) {
        CustomerEntity customer = mock(CustomerEntity.class);
        when(customer.getId()).thenReturn(id);
        when(customer.getCustomerNo()).thenReturn(customerNo);
        when(customer.isDeleted()).thenReturn(false);
        return customer;
    }
}
