package com.bank.aml.assistant.persistence.entity;

import com.bank.aml.assistant.domain.AssistantResultType;
import com.bank.aml.assistant.domain.AssistantRunStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantStateMachineTest {
    @Test
    void terminalRunCannotReturnToProcessingOrBeOverwritten() {
        AssistantRunEntity run = AssistantRunEntity.accepted("c", "u", "a");
        run.processing("CUSTOMER_ANALYSIS");
        run.complete(10, null, null);

        assertThatThrownBy(() -> run.processing("CUSTOMER_ANALYSIS"))
                .isInstanceOf(IllegalStateException.class);
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
}
