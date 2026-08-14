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
 * Pending 消息接管：定期检测执行超时的 RUNNING 工单（Worker 崩溃等），
 * 通过 {@link WorkflowCommandService} 置回 PENDING 并重新投递，实现任务恢复。
 * <p>入队统一写入 Outbox（不再直接写 Redis），由发布器异步投递。
 */
@Component
public class PendingClaimer {

    private static final Logger log = LoggerFactory.getLogger(PendingClaimer.class);

    private final CaseRepository caseRepository;
    private final WorkflowCommandService workflowCommandService;
    private final QueueProperties props;

    public PendingClaimer(CaseRepository caseRepository, WorkflowCommandService workflowCommandService,
                          QueueProperties props) {
        this.caseRepository = caseRepository;
        this.workflowCommandService = workflowCommandService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${aml.queue.claim-idle-seconds:60}000")
    public void reclaimStuckCases() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(props.getClaimIdleSeconds());
        List<CaseEntity> stuck = caseRepository.findByStatusAndLockedAtBefore(CaseStatus.RUNNING, threshold);
        for (CaseEntity c : stuck) {
            // 仅接管"租约过期且心跳过期"的任务；心跳活跃说明是慢任务（长模型调用），不接管
            LocalDateTime heartbeat = c.getHeartbeatAt();
            if (heartbeat != null && heartbeat.isAfter(threshold)) {
                continue;
            }
            // 防止无限接管：超过最大重试次数直接失败，转人工排查
            if (c.getRetryCount() >= props.getMaxRetry()) {
                if (workflowCommandService.failReclaimExhausted(c.getId())) {
                    log.error("工单 {} 多次接管失败，标记 FAILED", c.getId());
                }
                continue;
            }
            if (workflowCommandService.reclaimExpiredCase(c.getId(), c.getExecutionVersion())) {
                log.warn("接管超时工单 caseId={}，重新投递", c.getId());
            }
        }
    }
}
