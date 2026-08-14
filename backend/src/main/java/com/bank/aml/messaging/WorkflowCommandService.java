package com.bank.aml.messaging;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 工单入队命令服务：所有"状态迁移 + 入队"在同一事务内完成，入队统一写入 Outbox，
 * 由 {@link OutboxPublisher} 异步投递到 Redis Streams，避免状态变更与消息投递不一致。
 * <p>{@code RetryScheduler} / {@code PendingClaimer} / {@code QueueController} 均通过本服务入队，
 * 不再直接写 Redis，保证重试、接管、重放的入队路径可审计且幂等。
 */
@Service
public class WorkflowCommandService {

    private final CaseRepository caseRepository;
    private final OutboxService outboxService;

    public WorkflowCommandService(CaseRepository caseRepository, OutboxService outboxService) {
        this.caseRepository = caseRepository;
        this.outboxService = outboxService;
    }

    /** 工单创建首次入队 */
    @Transactional
    public void enqueueCaseCreated(Long caseId) {
        outboxService.record(caseId, WorkflowEventType.CASE_CREATED.name(), 0);
    }

    /** 手动触发（状态由调用方判定为 PENDING/FAILED） */
    @Transactional
    public void triggerManual(Long caseId, int executionVersion) {
        outboxService.record(caseId, WorkflowEventType.CASE_MANUAL_TRIGGERED.name(), executionVersion);
    }

    /** 人工重试：FAILED → PENDING（条件更新）+ 入队，非 FAILED 返回 409 */
    @Transactional
    public CaseEntity retryManual(Long caseId) {
        if (caseRepository.retryFailed(caseId, CaseStatus.PENDING, CaseStatus.FAILED) == 0) {
            CaseEntity c = caseRepository.findById(caseId)
                    .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
            throw new WorkflowStateConflictException(caseId, c.getStatus(), Set.of(CaseStatus.FAILED));
        }
        CaseEntity c = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        outboxService.record(caseId, WorkflowEventType.CASE_MANUAL_RETRIED.name(), c.getExecutionVersion());
        return c;
    }

    /** 重试到期：RETRY_WAIT → PENDING + 入队（条件更新保证并发下仅一次） */
    @Transactional
    public boolean scheduleDueRetry(Long caseId, int executionVersion) {
        if (caseRepository.requeueRetryWait(caseId, CaseStatus.PENDING, CaseStatus.RETRY_WAIT) == 1) {
            outboxService.record(caseId, WorkflowEventType.CASE_RETRY_DUE.name(), executionVersion);
            return true;
        }
        return false;
    }

    /** 接管超时：RUNNING → PENDING（retryCount+1）+ 入队；绑定 worker+version+heartbeat 阈值，心跳刷新后不误接管 */
    @Transactional
    public boolean reclaimExpiredCase(Long caseId, int executionVersion, String worker,
                                      LocalDateTime heartbeatThreshold) {
        if (caseRepository.reclaimStuckCase(caseId, CaseStatus.PENDING, CaseStatus.RUNNING,
                executionVersion, worker, heartbeatThreshold) == 1) {
            outboxService.record(caseId, WorkflowEventType.CASE_RECLAIMED.name(), executionVersion);
            return true;
        }
        return false;
    }

    /** 接管耗尽：RUNNING → FAILED（终态转人工，无需入队）；同样绑定 worker+version+heartbeat 阈值 */
    @Transactional
    public boolean failReclaimExhausted(Long caseId, int executionVersion, String worker,
                                        LocalDateTime heartbeatThreshold) {
        return caseRepository.failReclaimExhausted(caseId, CaseStatus.FAILED, CaseStatus.RUNNING,
                executionVersion, worker, heartbeatThreshold) == 1;
    }

    /** 重试超限进死信：RUNNING → FAILED + 死信 Outbox（同一事务，不直接写 Redis） */
    @Transactional
    public boolean markDeadLetter(Long caseId, String worker, int executionVersion, int retry, String message) {
        if (caseRepository.failCase(caseId, CaseStatus.FAILED, retry, "RETRY_EXHAUSTED", message,
                worker, executionVersion) == 1) {
            outboxService.record(caseId, WorkflowEventType.CASE_DEAD_LETTER.name(), executionVersion);
            return true;
        }
        return false;
    }

    /** 死信重放：FAILED → PENDING（条件更新，重置重试次数）+ 入队，非 FAILED 返回 409 */
    @Transactional
    public CaseEntity replayDead(Long caseId) {
        if (caseRepository.replayDeadLetter(caseId, CaseStatus.PENDING, CaseStatus.FAILED) == 0) {
            CaseEntity c = caseRepository.findById(caseId)
                    .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
            throw new WorkflowStateConflictException(caseId, c.getStatus(), Set.of(CaseStatus.FAILED));
        }
        CaseEntity c = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        outboxService.record(caseId, WorkflowEventType.CASE_DEAD_REPLAYED.name(), c.getExecutionVersion());
        return c;
    }
}
