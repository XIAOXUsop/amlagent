package com.bank.aml.messaging;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 重试调度器：扫描 RETRY_WAIT 且 nextRetryAt 已到期的工单，通过 {@link WorkflowCommandService} 重新置 PENDING 并入队。
 * 与 {@link WorkflowMessageHandler} 的指数退避配合，形成"失败 → RETRY_WAIT → 到期重投"的闭环。
 * <p>入队统一写入 Outbox（不再直接写 Redis），由发布器异步投递。
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final CaseRepository caseRepository;
    private final WorkflowCommandService workflowCommandService;

    public RetryScheduler(CaseRepository caseRepository, WorkflowCommandService workflowCommandService) {
        this.caseRepository = caseRepository;
        this.workflowCommandService = workflowCommandService;
    }

    @Scheduled(fixedDelayString = "${aml.queue.retry-poll-seconds:5}000")
    public void requeueDueRetries() {
        LocalDateTime now = LocalDateTime.now();
        List<CaseEntity> due = caseRepository.findByStatusAndNextRetryAtLessThanEqual(CaseStatus.RETRY_WAIT, now);
        for (CaseEntity c : due) {
            if (workflowCommandService.scheduleDueRetry(c.getId(), c.getExecutionVersion())) {
                log.info("重试到期，重新入队 caseId={} retry={}", c.getId(), c.getRetryCount());
            }
        }
    }
}
