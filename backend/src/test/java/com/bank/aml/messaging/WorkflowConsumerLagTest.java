package com.bank.aml.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.StreamInfo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowConsumerLagTest {
    @Test
    void includesUndeliveredAndPendingMessages() {
        StreamInfo.XInfoGroup group = StreamInfo.XInfoGroup.fromList(List.of(
                "name", "aml-workers",
                "consumers", 1L,
                "pending", 3L,
                "last-delivered-id", "10-0",
                "entries-read", 10L,
                "lag", 7L
        ));

        assertThat(WorkflowConsumer.totalGroupLag(group)).isEqualTo(10L);
    }
}
