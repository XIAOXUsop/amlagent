package com.bank.aml.messaging;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 重试调度器：扫描 RETRY_WAIT 且 nextRetryAt 已到期的工单，重新置 PENDING 并入队。
 * 与 {@link WorkflowMessageHandler} 的指数退避配合，形成"失败 → RETRY_WAIT → 到期重投"的闭环。
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final CaseRepository caseRepository;
    private final StringRedisTemplate redisTemplate;
    private final QueueProperties props;

    public RetryScheduler(CaseRepository caseRepository, StringRedisTemplate redisTemplate, QueueProperties props) {
        this.caseRepository = caseRepository;
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${aml.queue.retry-poll-seconds:5}000")
    public void requeueDueRetries() {
        LocalDateTime now = LocalDateTime.now();
        List<CaseEntity> due = caseRepository.findByStatusAndNextRetryAtLessThanEqual(CaseStatus.RETRY_WAIT, now);
        for (CaseEntity c : due) {
            // 条件更新，避免并发重复入队
            if (caseRepository.requeueRetryWait(c.getId(), CaseStatus.PENDING, CaseStatus.RETRY_WAIT) == 1) {
                redisTemplate.opsForStream().add(StreamRecords.string(
                        Map.of("caseId", String.valueOf(c.getId()))).withStreamKey(props.getStream()));
                log.info("重试到期，重新入队 caseId={} retry={}", c.getId(), c.getRetryCount());
            }
        }
    }
}
