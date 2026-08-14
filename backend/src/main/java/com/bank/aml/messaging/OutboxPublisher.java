package com.bank.aml.messaging;

import com.bank.aml.messaging.OutboxEvent.OutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Transactional Outbox 发布器：定时扫描待发布事件，投递到 Redis Streams。
 * <p>工单与事件同事务写入，此处实现"数据库 → 消息队列"的最终一致性投递。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final QueueProperties props;

    public OutboxPublisher(OutboxRepository outboxRepository, StringRedisTemplate redisTemplate,
                           QueueProperties props) {
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${aml.queue.outbox-poll-seconds:5}000")
    public void publishPending() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> pending = outboxRepository.findByStatusAndNextRetryAtLessThanEqualOrderByIdAsc(
                OutboxStatus.PENDING, now);
        if (pending.isEmpty()) {
            return;
        }
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        for (OutboxEvent event : pending) {
            try {
                String streamKey = isDeadLetter(event) ? props.getDeadStream() : props.getStream();
                ops.add(StreamRecords.string(Map.of(
                        "caseId", String.valueOf(event.getAggregateId()),
                        "eventType", event.getEventType(),
                        "executionVersion", String.valueOf(event.getExecutionVersion()),
                        "idempotencyKey", event.getIdempotencyKey() == null ? "" : event.getIdempotencyKey()
                )).withStreamKey(streamKey));
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(now);
                outboxRepository.save(event);
            } catch (Exception e) {
                int retry = event.getRetryCount() + 1;
                event.setRetryCount(retry);
                event.setNextRetryAt(now.plusSeconds((long) props.getRetryBackoffSeconds() * (1L << Math.min(retry, 6))));
                if (retry >= props.getMaxRetry()) {
                    event.setStatus(OutboxStatus.DEAD);
                }
                outboxRepository.save(event);
                log.error("Outbox 发布失败 eventId={} caseId={}", event.getId(), event.getAggregateId(), e);
            }
        }
    }

    private boolean isDeadLetter(OutboxEvent event) {
        return WorkflowEventType.CASE_DEAD_LETTER.name().equals(event.getEventType());
    }
}
