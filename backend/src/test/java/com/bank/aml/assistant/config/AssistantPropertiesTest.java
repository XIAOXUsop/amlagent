package com.bank.aml.assistant.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPropertiesTest {

    @Test
    void defaultsAreSafeAndLeaseWindowIsValid() {
        AssistantProperties properties = new AssistantProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getMaxMessageChars()).isEqualTo(2_000);
        assertThat(properties.getRetentionDays()).isEqualTo(7);
        assertThat(properties.getEventReadBlockSeconds()).isEqualTo(2);
        assertThat(properties.getEventHeartbeatSeconds()).isEqualTo(15);
        assertThat(properties.getRecoveryGraceSeconds()).isEqualTo(30);
        assertThat(properties.isLeaseWindowValid()).isTrue();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @Test
    void rejectsUnsafeCapacityAndLeaseValues() {
        AssistantProperties properties = new AssistantProperties();
        properties.setMaxMessageChars(50_000);
        properties.setLeaseTtlSeconds(30);
        properties.setLeaseRenewSeconds(20);
        properties.setEventReadBlockSeconds(0);
        properties.setEventHeartbeatSeconds(301);
        properties.setRecoveryGraceSeconds(601);
        properties.setTaskCorePoolSize(5);
        properties.setTaskMaxPoolSize(4);
        properties.setSseCorePoolSize(13);
        properties.setSseMaxPoolSize(12);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(properties);
            assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("maxMessageChars", "eventReadBlockSeconds", "eventHeartbeatSeconds", "recoveryGraceSeconds",
                        "leaseWindowValid");
            assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("taskPoolSizeValid", "ssePoolSizeValid");
        }
    }

}
