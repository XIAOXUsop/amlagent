package com.bank.aml.messaging;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueConfigurationValidatorTest {

    @Test
    void rejectsClaimWindowShorterThanTwoHeartbeats() {
        QueueProperties properties = new QueueProperties();
        properties.setHeartbeatSeconds(30);
        properties.setClaimIdleSeconds(45);

        assertThatThrownBy(() -> new QueueConfigurationValidator(properties).run(null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("两倍");
    }

    @Test
    void acceptsDefaultSixtySecondClaimWindow() {
        QueueProperties properties = new QueueProperties();

        new QueueConfigurationValidator(properties).run(null);

        assertThat(properties.getConsumerPollTimeoutMs()).isEqualTo(300);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isEmpty();
        }
    }

    @Test
    void rejectsConsumerPollTimeoutOutsideOperationalBounds() {
        QueueProperties properties = new QueueProperties();
        properties.setConsumerPollTimeoutMs(1);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).extracting(v -> v.getPropertyPath().toString())
                .contains("consumerPollTimeoutMs");
        }
    }

}
