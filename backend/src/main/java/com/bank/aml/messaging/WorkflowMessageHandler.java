package com.bank.aml.messaging;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.ManualReviewRequiredException;
import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.common.exception.RetryableWorkflowException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.service.DueDiligenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 尽调任务消费处理：
 * <ol>
 *   <li>条件更新抢占工单（幂等，避免重复执行）；</li>
 *   <li>执行工作流，按异常类型决定 ACK / 重试 / 死信；</li>
 *   <li>至少一次投递语义下的业务幂等由 executionVersion + 抢占保证。</li>
 * </ol>
 */
@Component
public class WorkflowMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMessageHandler.class);

    private final CaseRepository caseRepository;
    private final DueDiligenceService dueDiligenceService;
    private final StringRedisTemplate redisTemplate;
    private final QueueProperties props;
    private final WorkerIdentity workerIdentity;

    public WorkflowMessageHandler(CaseRepository caseRepository, DueDiligenceService dueDiligenceService,
                                  StringRedisTemplate redisTemplate, QueueProperties props,
                                  WorkerIdentity workerIdentity) {
        this.caseRepository = caseRepository;
        this.dueDiligenceService = dueDiligenceService;
        this.redisTemplate = redisTemplate;
        this.props = props;
        this.workerIdentity = workerIdentity;
    }

    public void onMessage(MapRecord<String, String, String> record) {
        Map<String, String> value = record.getValue();
        Long caseId;
        try {
            caseId = Long.parseLong(value.get("caseId"));
        } catch (Exception e) {
            ack(record);
            return;
        }

        // 抢占执行权：仅 PENDING/FAILED 可抢占，executionVersion 自增
        boolean locked = caseRepository.tryLock(caseId, workerIdentity.consumerName(), LocalDateTime.now(),
                CaseStatus.RUNNING, List.of(CaseStatus.PENDING, CaseStatus.FAILED)) == 1;
        if (!locked) {
            // 已在执行/已完成，幂等丢弃（重复消息不重复处理）
            ack(record);
            return;
        }

        // 心跳线程：长模型调用期间周期性刷新 heartbeatAt，避免被 PendingClaimer 错误接管
        java.util.concurrent.ScheduledExecutorService heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                caseRepository.updateHeartbeat(caseId, LocalDateTime.now());
            } catch (Exception ignored) {
                // 心跳失败不影响主流程
            }
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
        try {
            dueDiligenceService.process(caseId);
            ack(record);
        } catch (ManualReviewRequiredException e) {
            ack(record);
        } catch (NonRetryableWorkflowException e) {
            markFailed(caseId, "NON_RETRYABLE", e.getMessage());
            ack(record);
        } catch (RetryableWorkflowException e) {
            handleRetry(caseId, e.getMessage(), record);
        } catch (Exception e) {
            handleRetry(caseId, "未知异常: " + e.getMessage(), record);
        } finally {
            heartbeat.shutdownNow();
        }
    }

    /** 可重试失败：重试次数未超限则置 RETRY_WAIT（指数退避，由 RetryScheduler 到期重投）；超限进死信 */
    private void handleRetry(Long caseId, String message, MapRecord<String, String, String> record) {
        CaseEntity c = caseRepository.findById(caseId).orElse(null);
        int retry = (c == null ? 0 : c.getRetryCount()) + 1;
        if (retry >= props.getMaxRetry()) {
            redisTemplate.opsForStream().add(StreamRecords.string(
                    Map.of("caseId", String.valueOf(caseId))).withStreamKey(props.getDeadStream()));
            caseRepository.failCase(caseId, CaseStatus.FAILED, retry, "RETRY_EXHAUSTED", message);
            ack(record);
            log.error("工单重试超限进死信 caseId={} retry={}", caseId, retry);
        } else {
            long backoffSeconds = backoffSeconds(retry);
            LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
            caseRepository.markRetryWait(caseId, CaseStatus.RETRY_WAIT, retry, "RETRYABLE", message, nextRetryAt);
            ack(record);
            log.warn("工单可重试失败，进入 RETRY_WAIT caseId={} retry={} 退避={}s", caseId, retry, backoffSeconds);
        }
    }

    /** 指数退避：5s、15s、45s（base=5，比例 3） */
    private long backoffSeconds(int retry) {
        long base = props.getRetryBackoffSeconds();
        long factor = 1;
        for (int i = 1; i < retry; i++) {
            factor *= 3;
        }
        return base * factor;
    }

    private void markFailed(Long caseId, String code, String message) {
        CaseEntity c = caseRepository.findById(caseId).orElse(null);
        caseRepository.failCase(caseId, CaseStatus.FAILED,
                c == null ? 0 : c.getRetryCount(), code, message);
    }

    private void ack(MapRecord<String, String, String> record) {
        redisTemplate.opsForStream().acknowledge(props.getStream(), props.getGroup(), record.getId());
    }
}
