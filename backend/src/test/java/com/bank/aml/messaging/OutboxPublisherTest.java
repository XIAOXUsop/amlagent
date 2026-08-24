package com.bank.aml.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishesWithFencingTokenAndNeverMaxLenTrimsWorkStream() {
        OutboxRepository repository = mock(OutboxRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, String, String> operations = mock(StreamOperations.class);
        QueueProperties properties = new QueueProperties();
        OutboxEvent event = mock(OutboxEvent.class);
        when(event.getId()).thenReturn(11L);
        when(event.getAggregateId()).thenReturn(22L);
        when(event.getEventType()).thenReturn(WorkflowEventType.CASE_CREATED.name());
        when(event.getExecutionVersion()).thenReturn(3);
        when(event.getIdempotencyKey()).thenReturn("22:CASE_CREATED:3");
        when(event.getClaimVersion()).thenReturn(7L);
        when(repository.findPublishable(any(), any(), any(), any(), any())).thenReturn(List.of(event));
        when(repository.claimPublishing(eq(11L), any(), any(), anyString(), any(), any())).thenReturn(1);
        doReturn(operations).when(redis).opsForStream();
        when(operations.add(any())).thenReturn(RecordId.of("1-0"));

        new OutboxPublisher(repository, redis, properties).publishPending();

        verify(repository).markPublished(eq(11L), any(), any(), anyString(), eq(8L), any(LocalDateTime.class));
        verify(operations, never()).trim(anyString(), any(Long.class));
    }
}
