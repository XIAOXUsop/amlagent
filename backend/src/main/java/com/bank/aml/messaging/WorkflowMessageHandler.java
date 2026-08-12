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

    public WorkflowMessageHandler(CaseRepository caseRepository, DueDiligenceService dueDiligenceService,
                                  StringRedisTemplate redisTemplate, QueueProperties props) {
        this.caseRepository = caseRepository;
        this.dueDiligenceService = dueDiligenceService;
        this.redisTemplate = redisTemplate;
        this.props = props;
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
        boolean locked = caseRepository.tryLock(caseId, props.getConsumer(), LocalDateTime.now(),
                CaseStatus.RUNNING, List.of(CaseStatus.PENDING, CaseStatus.FAILED)) == 1;
        if (!locked) {
            // 已在执行/已完成，幂等丢弃（重复消息不重复处理）
            ack(record);
            return;
        }

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
        }
    }

    /** 可重试失败：重试次数未超限则置回 FAILED 并重新入队；超限进死信 */
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
            caseRepository.failCase(caseId, CaseStatus.FAILED, retry, "RETRYABLE", message);
            redisTemplate.opsForStream().add(StreamRecords.string(
                    Map.of("caseId", String.valueOf(caseId))).withStreamKey(props.getStream()));
            ack(record);
            log.warn("工单可重试失败，重新入队 caseId={} retry={}", caseId, retry);
        }
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
