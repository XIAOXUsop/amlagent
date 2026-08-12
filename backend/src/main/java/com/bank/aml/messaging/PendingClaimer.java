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
 * Pending 消息接管：定期检测执行超时的 RUNNING 工单（Worker 崩溃等），
 * 置回 PENDING 并重新投递，实现任务恢复。
 */
@Component
public class PendingClaimer {

    private static final Logger log = LoggerFactory.getLogger(PendingClaimer.class);

    private final CaseRepository caseRepository;
    private final StringRedisTemplate redisTemplate;
    private final QueueProperties props;

    public PendingClaimer(CaseRepository caseRepository, StringRedisTemplate redisTemplate, QueueProperties props) {
        this.caseRepository = caseRepository;
        this.redisTemplate = redisTemplate;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${aml.queue.claim-idle-seconds:60}000")
    public void reclaimStuckCases() {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(props.getClaimIdleSeconds());
        List<CaseEntity> stuck = caseRepository.findByStatusAndLockedAtBefore(CaseStatus.RUNNING, threshold);
        for (CaseEntity c : stuck) {
            c.setStatus(CaseStatus.PENDING);
            c.setLockedBy(null);
            c.setLockedAt(null);
            c.setFailureMessage("Worker 超时接管，重新投递");
            caseRepository.save(c);
            redisTemplate.opsForStream().add(StreamRecords.string(
                    Map.of("caseId", String.valueOf(c.getId()))).withStreamKey(props.getStream()));
            log.warn("接管超时工单 caseId={}，重新投递", c.getId());
        }
    }
}
