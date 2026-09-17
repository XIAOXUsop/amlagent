package com.bank.aml.workflow;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.messaging.OutboxEvent;
import com.bank.aml.messaging.OutboxRepository;
import com.bank.aml.messaging.OutboxService;
import com.bank.aml.messaging.PendingClaimer;
import com.bank.aml.messaging.QueueProperties;
import com.bank.aml.messaging.RetryScheduler;
import com.bank.aml.messaging.WorkflowCommandService;
import com.bank.aml.messaging.WorkflowEventType;
import com.bank.aml.service.DueDiligenceService;
import com.bank.aml.testinfra.IntegrationTestDatabase;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可靠工作流集成测试（确定性，直接驱动组件，不依赖后台异步 Worker，避免 Redis 消费时序抖动）。
 * <p>
 * 覆盖任务书的可靠性闭环：Outbox 幂等、退避重投、超时接管（Redis 恢复）、死信兜底、原子租约。 复用本机 Docker 的
 * MySQL/Redis/PGVector。运行：./mvnw -Pintegration-test test
 * <p>
 * 使用独立 Redis Stream 名称，避免与其他集成测试（WorkflowE2ETest）共享消费者组产生消息投递抖动； 本类运行前强制销毁先前缓存的 Spring
 * 上下文，防止其他上下文的后台 Outbox 发布器与本类共享 outbox 表时互相抢占投递。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class ReliabilityWorkflowTest {

    @DynamicPropertySource
    static void isolatedRedisStream(DynamicPropertyRegistry registry) {
        IntegrationTestDatabase.configure(registry, "aml_reliability_test");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        registry.add("aml.queue.stream", () -> "aml:workflow:cases-test-" + suffix);
        registry.add("aml.queue.dead-stream", () -> "aml:workflow:dead-test-" + suffix);
        registry.add("aml.queue.group", () -> "aml-workers-test-" + suffix);
        // 这个类断言的是**状态机**：调度器把工单摆成什么状态。
        // 后台消费者会立刻把它捡起来继续跑，于是"刚摆好"的状态被改掉，
        // 断言变成看运气。这里显式关掉消费者，让被测的调度器行为可确定。
    }

    @Autowired
    private DueDiligenceService service;

    @Autowired
    private CaseRepository caseRepository;

    /**
     * 与应用**同一个** Clock。
     *
     * <p>
     * 应用里所有时间基准都是 {@code LocalDateTime.now(clock)}（UTC）， 而这些用例原先用
     * {@code LocalDateTime.now(clock)}（系统本地时区）造"已到期"的时间戳。 在 UTC+8 的机器上那等于把时间戳推到 8
     * 小时后——"已到期的重试"其实还没到期， "120 秒没心跳"其实才刚心跳过。表现就是调度器看起来什么都没做。
     */
    @Autowired
    private Clock clock;

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

    /** 发布 fencing：旧 Publisher 的 owner/version 在 Claim 被接管后不能确认新 Claim。 */
    @Test
    void staleOutboxPublisherCannotAcknowledgeReclaimedClaim() {
        OutboxEvent event = new OutboxEvent();
        event.setAggregateId(991001L);
        event.setEventType(WorkflowEventType.CASE_CREATED.name());
        event.setExecutionVersion(0);
        event.setIdempotencyKey("991001:fencing:0");
        event = outboxRepository.saveAndFlush(event);
        LocalDateTime firstClaimAt = LocalDateTime.now(clock).minusMinutes(2);

        assertThat(outboxRepository.claimPublishing(event.getId(), OutboxEvent.OutboxStatus.PUBLISHING,
                OutboxEvent.OutboxStatus.PENDING, "publisher-a", firstClaimAt, firstClaimAt.minusSeconds(30)))
            .isEqualTo(1);
        assertThat(outboxRepository.claimPublishing(event.getId(), OutboxEvent.OutboxStatus.PUBLISHING,
                OutboxEvent.OutboxStatus.PENDING, "publisher-b", LocalDateTime.now(clock),
                LocalDateTime.now(clock).minusSeconds(30)))
            .isEqualTo(1);

        assertThat(outboxRepository.markPublished(event.getId(), OutboxEvent.OutboxStatus.PUBLISHED,
                OutboxEvent.OutboxStatus.PUBLISHING, "publisher-a", 1L, LocalDateTime.now(clock)))
            .isZero();
        assertThat(outboxRepository.markPublished(event.getId(), OutboxEvent.OutboxStatus.PUBLISHED,
                OutboxEvent.OutboxStatus.PUBLISHING, "publisher-b", 2L, LocalDateTime.now(clock)))
            .isEqualTo(1);
    }

    /** 幂等：同一 caseId:eventType:executionVersion 只落一条 Outbox 事件 */
    @Test
    void outboxRecordIsIdempotentByKey() {
        long caseId = 900000L + System.currentTimeMillis() % 10000;
        outboxService.record(caseId, WorkflowEventType.CASE_RETRY_DUE.name(), 7);
        outboxService.record(caseId, WorkflowEventType.CASE_RETRY_DUE.name(), 7);

        String key = OutboxService.idempotencyKey(caseId, WorkflowEventType.CASE_RETRY_DUE.name(), 7);
        long count = outboxRepository.findAll().stream().filter(e -> key.equals(e.getIdempotencyKey())).count();
        assertThat(count).isEqualTo(1);
    }

    /** 退避重投：RETRY_WAIT 到期 → 重新置 PENDING 并生成 CASE_RETRY_DUE 入队事件 */
    @Test
    void retrySchedulerRequeuesDueRetry() {
        CaseEntity c = service.createCase("C002", "常规监测", false);
        c.setStatus(CaseStatus.RETRY_WAIT);
        c.setRetryCount(1);
        c.setExecutionVersion(1);
        c.setNextRetryAt(LocalDateTime.now(clock).minusSeconds(1)); // 已到期
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
        c.setLockedAt(LocalDateTime.now(clock).minusSeconds(120));
        c.setHeartbeatAt(LocalDateTime.now(clock).minusSeconds(120));
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
        c.setLockedAt(LocalDateTime.now(clock).minusSeconds(120));
        c.setHeartbeatAt(LocalDateTime.now(clock).minusSeconds(120));
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

        int stale = caseRepository.failCase(c.getId(), CaseStatus.FAILED, 3, "RETRY_EXHAUSTED", "旧 Worker 陈旧写入",
                "worker-old", 4);
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
        boolean marked = workflowCommandService.markDeadLetter(c.getId(), "worker-x", 2, props.getMaxRetry(),
                "重试超限进死信");
        assertThat(marked).isTrue();

        CaseEntity failed = service.getCase(c.getId());
        assertThat(failed.getStatus()).isEqualTo(CaseStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("RETRY_EXHAUSTED");
        assertThat(failed.getRetryCount()).isEqualTo(props.getMaxRetry());

        String key = OutboxService.idempotencyKey(c.getId(), WorkflowEventType.CASE_DEAD_LETTER.name(), 2);
        assertThat(outboxRepository.existsByIdempotencyKey(key)).isTrue();
    }

}
