package com.bank.aml.messaging;

import com.bank.aml.messaging.OutboxEvent.OutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional Outbox 发布器：定时扫描待发布事件，投递到 Redis Streams。
 * <p>工单与事件同事务写入，此处实现"数据库 → 消息队列"的最终一致性投递。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** 发布 Claim 超时（秒）：XADD 单次操作耗时毫秒级，远超该阈值即视为陈旧 Claim，可被重新抢占 */
    private static final long CLAIM_STALE_SECONDS = 30;
    private static final int PUBLISH_BATCH_SIZE = 200;

    private final OutboxRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final QueueProperties props;
    private final String publisherId = "publisher-" + UUID.randomUUID().toString().substring(0, 12);

    public OutboxPublisher(OutboxRepository outboxRepository, StringRedisTemplate redisTemplate,
                           QueueProperties props) {
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${aml.queue.outbox-poll-seconds:5}000")
    public void publishPending() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusSeconds(CLAIM_STALE_SECONDS);
        List<OutboxEvent> pending = outboxRepository.findPublishable(
                OutboxStatus.PENDING, OutboxStatus.PUBLISHING, now, staleBefore,
                PageRequest.of(0, PUBLISH_BATCH_SIZE));
        if (pending.isEmpty()) {
            return;
        }
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        for (OutboxEvent event : pending) {
            publishOne(ops, event, now, staleBefore);
        }
    }

    /**
     * 发布单个事件（Claim-first）：
     * <ol>
     *   <li>原子抢占 PENDING → PUBLISHING（多发布器并发/多实例只有一个赢家，杜绝重复投递与发错 Stream）；</li>
     *   <li>XADD 投递到 Stream 后置 PUBLISHED；</li>
     *   <li>XADD 失败则释放抢占回退 PENDING 并按指数退避重试；崩溃残留由 {@code findPublishable} 陈旧 Claim 回收。</li>
     * </ol>
     */
    private void publishOne(StreamOperations<String, String, String> ops, OutboxEvent event,
                            LocalDateTime now, LocalDateTime staleBefore) {
        String streamKey = isDeadLetter(event) ? props.getDeadStream() : props.getStream();
        // 1. 原子抢占：只允许 PENDING 或超时的陈旧 PUBLISHING 被抢占
        int claimed = outboxRepository.claimPublishing(event.getId(), OutboxStatus.PUBLISHING,
                OutboxStatus.PENDING, publisherId, now, staleBefore);
        if (claimed == 0) {
            // 已被其他发布器抢占（PENDING → PUBLISHING），本次跳过，避免重复投递
            log.debug("Outbox 事件已被其他发布器抢占，跳过 eventId={} caseId={}", event.getId(), event.getAggregateId());
            return;
        }
        long claimVersion = event.getClaimVersion() + 1;
        try {
            // 2. 投递到 Stream（消息内容与之前一致，含幂等键供消费端去重）
            ops.add(StreamRecords.string(Map.of(
                    "caseId", String.valueOf(event.getAggregateId()),
                    "eventType", event.getEventType(),
                    "executionVersion", String.valueOf(event.getExecutionVersion()),
                    "idempotencyKey", event.getIdempotencyKey() == null ? "" : event.getIdempotencyKey()
            )).withStreamKey(streamKey));
            // 工作流 Stream 不做 MAXLEN 裁剪：Redis 的长度裁剪不保护 Pending 消息正文，
            // 可能让尚未 ACK 的任务只剩 PEL 引用却无法恢复。容量治理必须基于所有消费组安全位点单独执行。
            // 3. 绑定 owner+version 确认；陈旧发布器即使 XADD 较晚也无权覆盖新 Claim。
            outboxRepository.markPublished(event.getId(), OutboxStatus.PUBLISHED, OutboxStatus.PUBLISHING,
                    publisherId, claimVersion, now);
        } catch (Exception e) {
            int retry = event.getRetryCount() + 1;
            if (retry >= props.getMaxRetry()) {
                // 达到最大重试：PUBLISHING → DEAD（供人工排查），不丢失业务工单状态
                outboxRepository.failDead(event.getId(), OutboxStatus.DEAD, OutboxStatus.PUBLISHING,
                        publisherId, claimVersion, retry);
                log.error("Outbox 发布重试超限进 DEAD eventId={} caseId={}", event.getId(), event.getAggregateId(), e);
            } else {
                // 释放抢占回退 PENDING + 指数退避，等待下一轮重试（不丢失事件）
                LocalDateTime nextRetryAt = now.plusSeconds(
                        (long) props.getRetryBackoffSeconds() * (1L << Math.min(retry, 6)));
                outboxRepository.releaseClaim(event.getId(), OutboxStatus.PENDING, OutboxStatus.PUBLISHING,
                        publisherId, claimVersion, retry, nextRetryAt);
                log.error("Outbox 发布失败，等待重试 eventId={} caseId={} retry={}",
                        event.getId(), event.getAggregateId(), retry, e);
            }
        }
    }

    private boolean isDeadLetter(OutboxEvent event) {
        return WorkflowEventType.CASE_DEAD_LETTER.name().equals(event.getEventType());
    }

}
