package com.bank.aml.assistant.application;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantConversationStatus;
import com.bank.aml.assistant.persistence.entity.AssistantConversationEntity;
import com.bank.aml.assistant.persistence.repository.AssistantConversationRepository;
import com.bank.aml.datasource.entity.CustomerEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssistantRetentionServiceTest {
    @Test
    void expiresOnlyRepositorySelectedDueBatchWithoutDeletingAuditData() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        AssistantConversationRepository repository = mock(AssistantConversationRepository.class);
        CustomerEntity customer = mock(CustomerEntity.class);
        when(customer.getId()).thenReturn(7L);
        when(customer.getCustomerNo()).thenReturn("C-007");
        var conversation = AssistantConversationEntity.create("admin", customer, LocalDateTime.now().minusMinutes(1));
        when(repository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
                .thenReturn(List.of(conversation));

        int expired = new AssistantRetentionService(properties, repository).expireDueConversations();

        assertThat(expired).isEqualTo(1);
        assertThat(conversation.getStatus()).isEqualTo(AssistantConversationStatus.EXPIRED);
        verify(repository).saveAll(List.of(conversation));
        verify(repository, never()).delete(any());
    }
}
