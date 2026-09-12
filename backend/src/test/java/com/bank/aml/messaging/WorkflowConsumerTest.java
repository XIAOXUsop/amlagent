package com.bank.aml.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowConsumerTest {

    @Test
    void onlyBusyGroupFailureIsTreatedAsExistingConsumerGroup() {
        RuntimeException alreadyExists = new IllegalStateException("wrapper",
                new RuntimeException("BUSYGROUP Consumer Group name already exists"));
        RuntimeException permissionDenied = new RuntimeException("NOPERM this user has no permissions");

        assertThat(WorkflowConsumer.isConsumerGroupAlreadyExists(alreadyExists)).isTrue();
        assertThat(WorkflowConsumer.isConsumerGroupAlreadyExists(permissionDenied)).isFalse();
    }

}
