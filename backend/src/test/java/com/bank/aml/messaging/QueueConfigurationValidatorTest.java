package com.bank.aml.messaging;

import org.junit.jupiter.api.Test;

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
        new QueueConfigurationValidator(new QueueProperties()).run(null);
    }
}
