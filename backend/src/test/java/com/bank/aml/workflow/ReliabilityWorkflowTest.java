package com.bank.aml.workflow;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.messaging.OutboxRepository;
import com.bank.aml.messaging.OutboxService;
import com.bank.aml.messaging.PendingClaimer;
import com.bank.aml.messaging.QueueProperties;
import com.bank.aml.messaging.RetryScheduler;
import com.bank.aml.messaging.WorkflowCommandService;
import com.bank.aml.messaging.WorkflowEventType;
import com.bank.aml.service.DueDiligenceService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可靠工作流集成测试（确定性，直接驱动组件，不依赖后台异步 Worker，避免 Redis 消费时序抖动）。
 * <p>覆盖任务书的可靠性闭环：Outbox 幂等、退避重投、超时接管（Redis 恢复）、死信兜底、原子租约。
 * 复用本机 Docker 的 MySQL/Redis/PGVector。运行：./mvnw test -Dgroups=integration
 * <p>使用独立 Redis Stream 名称，避免与其他集成测试（WorkflowE2ETest）共享消费者组产生消息投递抖动；
 * 本类运行前强制销毁先前缓存的 Spring 上下文，防止其他上下文的后台 Outbox 发布器与本类共享 outbox 表时互相抢占投递。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ReliabilityWorkflowTest {

    @DynamicPropertySource
    static void isolatedRedisStream(DynamicPropertyRegistry registry) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        registry.add("aml.queue.stream", () -> "aml:workflow:cases-test-" + suffix);
        registry.add("aml.queue.dead-stream", () -> "aml:workflow:dead-test-" + suffix);
        registry.add("aml.queue.group", () -> "aml-workers-test-" + suffix);
    }

    @Autowired
    private DueDiligenceService service;
    @Autowired
    private CaseRepository caseRepository;
    @Autowired
    private OutboxService outboxService;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private RetryScheduler retryScheduler;
    @Autowired
    private WorkflowCommandService workflowCommandService;
    @Autowired
    private PendingClaimer pendingClaimer;
    @Autowired
    private QueueProperties props;

    /** 幂等：同一 caseId:eventType:executionVersion 只落一条 Outbox 事件 */
    @Test
    void outboxRecordIsIdempotentByKey() {
        long caseId = 900000L + System.currentTimeMillis() % 10000;
        outboxService.record(caseId, WorkflowEventType.CASE_RETRY_DUE.name(), 7);
        outboxService.record(caseId, WorkflowEventType.CASE_RETRY_DUE.name(), 7);

        String key = OutboxService.idempotencyKey(caseId, WorkflowEventType.CASE_RETRY_DUE.name(), 7);
        long count = outboxRepository.findAll().stream()
                .filter(e -> key.equals(e.getIdempotencyKey()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    /** 退避重投：RETRY_WAIT 到期 → 重新置 PENDING 并生成 CASE_RETRY_DUE 入队事件 */
    @Test
    void retrySchedulerRequeuesDueRetry() {
        CaseEntity c = service.createCase("C002", "常规监测", false);
        c.setStatus(CaseStatus.RETRY_WAIT);
        c.setRetryCount(1);
        c.setExecutionVersion(1);
        c.setNextRetryAt(LocalDateTime.now().minusSeconds(1)); // 已到期
        caseRepository.save(c);

        retryScheduler.requeueDueRetries();

        CaseEntity after = service.getCase(c.getId());
        assertThat(after.getStatus()).isEqualTo(CaseStatus.PENDING);
        assertThat(after.getNextRetryAt()).isNull();
        assertThat(after.getRetryCount()).isEqualTo(1);
        String key = OutboxService.idempotencyKey(c.getId(), WorkflowEventType.CASE_RETRY_DUE.name(), 1);
        assertThat(outboxRepository.existsByIdempotencyKey(key)).isTrue();
    }

    /** 接管 / Redis 恢复：租约与心跳均过期的 RUNNING 工单被接管重新投递 */
    @Test
    void pendingClaimerReclaimsExpiredCase() {
        CaseEntity c = service.createCase("C002", "常规监测", false);
        c.setStatus(CaseStatus.RUNNING);
        c.setLockedBy("worker-crashed");
        c.setLockedAt(LocalDateTime.now().minusSeconds(120));
        c.setHeartbeatAt(LocalDateTime.now().minusSeconds(120));
        c.setExecutionVersion(1);
        caseRepository.save(c);

        pendingClaimer.reclaimStuckCases();

        CaseEntity after = service.getCase(c.getId());
        assertThat(after.getStatus()).isEqualTo(CaseStatus.PENDING);
        assertThat(after.getLockedBy()).isNull();
        assertThat(after.getRetryCount()).isEqualTo(1);
        String key = OutboxService.idempotencyKey(c.getId(), WorkflowEventType.CASE_RECLAIMED.name(), 1);
        assertThat(outboxRepository.existsByIdempotencyKey(key)).isTrue();
    }

    /** 接管耗尽：重试次数已达上限 → 标记 FAILED 转人工，不再无限接管 */
    @Test
    void pendingClaimerMarksExhaustedAsFailed() {
        CaseEntity c = service.createCase("C002", "常规监测", false);
        c.setStatus(CaseStatus.RUNNING);
        c.setLockedBy("worker-crashed");
        c.setLockedAt(LocalDateTime.now().minusSeconds(120));
        c.setHeartbeatAt(LocalDateTime.now().minusSeconds(120));
        c.setRetryCount(props.getMaxRetry()); // 已达上限
        caseRepository.save(c);

        pendingClaimer.reclaimStuckCases();

        CaseEntity after = service.getCase(c.getId());
        assertThat(after.getStatus()).isEqualTo(CaseStatus.FAILED);
        assertThat(after.getFailureCode()).isEqualTo("CLAIM_EXHAUSTED");
    }

    /** 原子租约：旧 Worker（executionVersion 不匹配）的失败写入被拒绝，不覆盖已接管工单 */
    @Test
    void staleWorkerCannotFailReclaimedCase() {
        CaseEntity c = service.createCase("C002", "常规监测", false);
        c.setStatus(CaseStatus.RUNNING);
        c.setLockedBy("worker-current");
        c.setExecutionVersion(5);
        caseRepository.save(c);

        int stale = caseRepository.failCase(c.getId(), CaseStatus.FAILED, 3,
                "RETRY_EXHAUSTED", "旧 Worker 陈旧写入", "worker-old", 4);
        assertThat(stale).isEqualTo(0);
        assertThat(service.getCase(c.getId()).getStatus()).isEqualTo(CaseStatus.RUNNING);
    }

    /** 死信兜底：重试超限 → FAILED（RETRY_EXHAUSTED）+ 死信 Outbox 事件（由发布器异步投递到 Dead Stream） */
    @Test
    void retryExhaustionMarksFailedAndEnqueuesDeadLetter() {
        CaseEntity c = service.createCase("C002", "常规监测", false);
        c.setStatus(CaseStatus.RUNNING);
        c.setLockedBy("worker-x");
        c.setExecutionVersion(2);
        c.setRetryCount(props.getMaxRetry() - 1); // 下一次失败即超限
        caseRepository.save(c);

        // 走 markDeadLetter：FAILED 与死信 Outbox 同事务（不再直接写 Redis）
        boolean marked = workflowCommandService.markDeadLetter(c.getId(), "worker-x", 2,
                props.getMaxRetry(), "重试超限进死信");
        assertThat(marked).isTrue();

        CaseEntity failed = service.getCase(c.getId());
        assertThat(failed.getStatus()).isEqualTo(CaseStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(failed.getRetryCount()).isEqualTo(props.getMaxRetry());

        String key = OutboxService.idempotencyKey(c.getId(), WorkflowEventType.CASE_DEAD_LETTER.name(), 2);
        assertThat(outboxRepository.existsByIdempotencyKey(key)).isTrue();
    }
}
