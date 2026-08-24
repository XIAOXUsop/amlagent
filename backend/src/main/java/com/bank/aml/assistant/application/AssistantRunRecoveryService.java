package com.bank.aml.assistant.application;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantRunStatus;
import com.bank.aml.assistant.persistence.repository.AssistantRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 启动恢复只终结失联 run，不自动重放外部模型调用，避免重复计费和重复副作用。 */
@Service
public class AssistantRunRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AssistantRunRecoveryService.class);
    private final AssistantProperties properties;
    private final AssistantRunRepository runs;
    private final AssistantRunStateService state;

    public AssistantRunRecoveryService(AssistantProperties properties, AssistantRunRepository runs,
                                       AssistantRunStateService state) {
        this.properties = properties;
        this.runs = runs;
        this.state = state;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverTimedOutRuns() {
        if (!properties.isEnabled()) return;
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(properties.getRunTimeoutSeconds() + 30L);
        var abandoned = runs.findTop100ByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
                List.of(AssistantRunStatus.ACCEPTED, AssistantRunStatus.PROCESSING), cutoff);
        int recovered = 0;
        for (var run : abandoned) {
            try {
                state.fail(run.getId(), "AI 小助上次执行意外中断，请重新提问。", "APPLICATION_RESTARTED", 0);
                recovered++;
            } catch (RuntimeException exception) {
                log.warn("AI 小助启动恢复失败 runId={} type={}", run.getId(), exception.getClass().getSimpleName());
            }
        }
        if (recovered > 0) log.info("AI 小助已终结失联 run 数量={}", recovered);
    }
}
