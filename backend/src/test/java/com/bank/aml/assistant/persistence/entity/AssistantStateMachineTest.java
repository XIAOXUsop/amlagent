package com.bank.aml.assistant.persistence.entity;

import com.bank.aml.assistant.domain.AssistantResultType;
import com.bank.aml.assistant.domain.AssistantRunStatus;
import com.bank.aml.datasource.entity.CustomerEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantStateMachineTest {

    @Test
    void terminalRunCannotReturnToProcessingOrBeOverwritten() {
        AssistantRunEntity run = AssistantRunEntity.accepted("c", "u", "a");
        run.processing("CUSTOMER_ANALYSIS");
        run.complete(10, null, null);

        assertThatThrownBy(() -> run.processing("CUSTOMER_ANALYSIS")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.terminal(AssistantRunStatus.FAILED, "LATE_CALLBACK", 20))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void assistantMessageCanOnlyReachOneTerminalState() {
        AssistantMessageEntity message = AssistantMessageEntity.assistantPlaceholder("c", 2);
        message.complete("ok", AssistantResultType.ANSWERED);
        assertThatThrownBy(() -> message.fail("late", AssistantResultType.MODEL_UNAVAILABLE))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void conversationActivityUsesCallerSuppliedTime() {
        LocalDateTime expiry = LocalDateTime.of(2026, 9, 12, 12, 0);
        CustomerEntity customer = mock(CustomerEntity.class);
        when(customer.getId()).thenReturn(7L);
        when(customer.getCustomerNo()).thenReturn("C-007");
        AssistantConversationEntity conversation = AssistantConversationEntity.create("analyst", customer, expiry);

        assertThat(conversation.isActiveAt(expiry.minusNanos(1))).isTrue();
        assertThat(conversation.isActiveAt(expiry)).isFalse();
        conversation.archive();
        assertThat(conversation.isActiveAt(expiry.minusDays(1))).isFalse();
    }

}
