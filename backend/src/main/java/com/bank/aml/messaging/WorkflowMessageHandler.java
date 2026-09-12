package com.bank.aml.messaging;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.common.exception.RetryableWorkflowException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 尽调任务消费处理：
 * <ol>
 * <li>条件更新抢占工单（幂等，避免重复执行），并记录 worker + executionVersion；</li>
 * <li>执行工作流，按异常类型决定 ACK / 重试 / 死信；</li>
 * <li>心跳 / 完成 / 失败均绑定 worker+executionVersion，被接管后的陈旧写入不生效；</li>
 * <li>至少一次投递语义下的业务幂等由 executionVersion + 抢占保证。</li>
 * </ol>
 */
@Component
public class WorkflowMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowMessageHandler.class);

    private final CaseRepository caseRepository;

    private final WorkflowProcessor workflowProcessor;

    private final WorkflowCommandService workflowCommandService;

    private final StringRedisTemplate redisTemplate;

    private final QueueProperties props;

    private final WorkerIdentity workerIdentity;

    private final ScheduledExecutorService heartbeatExecutor;

    private final StreamConsumptionTracker consumptionTracker;

    private final WorkflowEventPort workflowEventService;

    private final Clock clock;

    public WorkflowMessageHandler(CaseRepository caseRepository, WorkflowProcessor workflowProcessor,
            WorkflowCommandService workflowCommandService, StringRedisTemplate redisTemplate, QueueProperties props,
            WorkerIdentity workerIdentity, ScheduledExecutorService heartbeatExecutor,
            StreamConsumptionTracker consumptionTracker, WorkflowEventPort workflowEventService, Clock clock) {
        this.caseRepository = caseRepository;
        this.workflowProcessor = workflowProcessor;
        this.workflowCommandService = workflowCommandService;
        this.redisTemplate = redisTemplate;
        this.props = props;
        this.workerIdentity = workerIdentity;
        this.heartbeatExecutor = heartbeatExecutor;
        this.consumptionTracker = consumptionTracker;
        this.workflowEventService = workflowEventService;
        this.clock = clock;
    }

    public void onMessage(MapRecord<String, String, String> record) {
        // 用 caseId 作为 MDC 上下文，使本次 Worker 处理的所有日志（Agent/Guardrail/落库）可按工单聚合
        String caseIdFromMsg = record.getValue().get("caseId");
        if (caseIdFromMsg != null) {
            MDC.put("caseId", caseIdFromMsg);
        }
        try {
            onMessageInternal(record);
        }
        finally {
            MDC.remove("caseId");
        }
    }

    private void onMessageInternal(MapRecord<String, String, String> record) {
        Map<String, String> value = record.getValue();
        Long caseId;
        try {
            caseId = Long.parseLong(value.get("caseId"));
        }
        catch (NumberFormatException e) {
            ack(record);
            return;
        }

        int expectedVersion = parseVersion(value.get("executionVersion"));
        if (expectedVersion < 0) {
            // 消息版本缺失或格式错误：记录并 ACK 丢弃，避免畸形旧消息抢占版本为 0 的工单
            log.warn("消息版本无效，丢弃 caseId={} executionVersion={}", caseId, value.get("executionVersion"));
            ack(record);
            return;
        }
        String worker = workerIdentity.consumerName();
        // 抢占执行权：仅 PENDING 可抢占（FAILED 只能通过显式管理命令恢复）；消息版本不匹配则丢弃
        boolean locked = caseRepository.tryLock(caseId, worker, LocalDateTime.now(clock), CaseStatus.RUNNING,
                List.of(CaseStatus.PENDING), expectedVersion) == 1;
        if (!locked) {
            // 已在执行/已完成，幂等丢弃（重复消息不重复处理）
            ack(record);
            return;
        }
        CaseEntity claimed = caseRepository.findById(caseId).orElse(null);
        int executionVersion = claimed == null ? 0 : claimed.getExecutionVersion();
        ExecutionLease lease = new ExecutionLease(caseId, executionVersion, worker);

        // 心跳线程：长模型调用期间周期性刷新 heartbeatAt，避免被 PendingClaimer 错误接管；
        // 心跳绑定 worker+executionVersion，返回 0 说明租约已丢失，标记 leaseLost 停止推进。
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                int updated = caseRepository.updateHeartbeat(caseId, worker, executionVersion,
                        LocalDateTime.now(clock));
                if (updated == 0) {
                    lease.markLost();
                    log.warn("工单 {} 心跳刷新失败（租约已丢失），停止推进业务阶段", caseId);
                }
            }
            catch (Exception heartbeatFailure) {
                log.warn("工作流心跳失败 caseId={} executionVersion={}", caseId, executionVersion, heartbeatFailure);
            }
        }, props.getHeartbeatSeconds(), props.getHeartbeatSeconds(), TimeUnit.SECONDS);
        try {
            workflowProcessor.processWorkflow(caseId, worker, executionVersion, lease);
            ack(record);
        }
        catch (NonRetryableWorkflowException e) {
            log.warn("工作流发生不可重试失败 caseId={} executionVersion={} type={}", caseId, executionVersion,
                    e.getClass().getSimpleName());
            markFailed(caseId, worker, executionVersion, "NON_RETRYABLE", "工作流执行失败，请联系管理员");
            ack(record);
        }
        catch (RetryableWorkflowException e) {
            log.warn("工作流发生可重试失败 caseId={} executionVersion={} type={}", caseId, executionVersion,
                    e.getClass().getSimpleName());
            handleRetry(caseId, worker, executionVersion, "工作流依赖暂时不可用，请稍后重试", record);
        }
        catch (NumberFormatException e) {
            log.error("工作流发生未分类异常 caseId={} executionVersion={}", caseId, executionVersion, e);
            handleRetry(caseId, worker, executionVersion, "工作流执行异常，请稍后重试", record);
        }
        finally {
            heartbeat.cancel(true);
        }
    }

    /** 可重试失败：重试次数未超限则置 RETRY_WAIT（指数退避，由 RetryScheduler 到期重投）；超限进死信 */
    private void handleRetry(Long caseId, String worker, int executionVersion, String message,
            MapRecord<String, String, String> record) {
        CaseEntity c = caseRepository.findById(caseId).orElse(null);
        int retry = (c == null ? 0 : c.getRetryCount()) + 1;
        if (retry >= props.getMaxRetry()) {
            // 死信走 Outbox：RUNNING → FAILED 与死信事件同事务，不直接写 Redis
            boolean failed = workflowCommandService.markDeadLetter(caseId, worker, executionVersion, retry, message);
            if (failed) {
                workflowEventService.complete(caseId, CaseStatus.FAILED);
            }
            ack(record);
            log.error("工单重试超限进死信 caseId={} retry={}", caseId, retry);
        }
        else {
            long backoffSeconds = backoffSeconds(retry);
            LocalDateTime nextRetryAt = LocalDateTime.now(clock).plusSeconds(backoffSeconds);
            caseRepository.markRetryWait(caseId, CaseStatus.RETRY_WAIT, retry, "RETRYABLE", message, nextRetryAt,
                    worker, executionVersion);
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

    private void markFailed(Long caseId, String worker, int executionVersion, String code, String message) {
        CaseEntity c = caseRepository.findById(caseId).orElse(null);
        int updated = caseRepository.failCase(caseId, CaseStatus.FAILED, c == null ? 0 : c.getRetryCount(), code,
                message, worker, executionVersion);
        if (updated == 1) {
            workflowEventService.complete(caseId, CaseStatus.FAILED);
        }
    }

    private void ack(MapRecord<String, String, String> record) {
        redisTemplate.opsForStream().acknowledge(props.getStream(), props.getGroup(), record.getId());
        consumptionTracker.recordAck();
    }

    private int parseVersion(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

}
