package com.bank.aml.common.fault;

import com.bank.aml.common.enums.WorkflowStage;
import com.bank.aml.common.exception.RetryableWorkflowException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 故障注入器（默认关闭）：用于可靠性演示，可一键模拟工作流阶段失败，
 * 触发"可重试失败 → 指数退避重试 → 超限进死信 → 人工重试恢复"完整链路。
 * <p>默认零开销：仅在显式启用后才会抛出异常。
 */
@Component
public class FaultInjector {

    private volatile boolean enabled = false;
    private final AtomicInteger remainingFailures = new AtomicInteger(0);
    private final AtomicInteger injectedCount = new AtomicInteger(0);

    /** 在指定阶段按需注入可重试失败（关闭时为空操作） */
    public void inject(WorkflowStage stage) {
        if (!enabled) {
            return;
        }
        if (remainingFailures.get() > 0) {
            remainingFailures.decrementAndGet();
            injectedCount.incrementAndGet();
            throw new RetryableWorkflowException("故障注入：模拟 " + stage + " 阶段失败（第 "
                    + injectedCount.get() + " 次）");
        }
    }

    /** 开启注入：后续 failCount 次调用 inject 会抛异常 */
    public void enable(int failCount) {
        this.remainingFailures.set(Math.max(0, failCount));
        this.injectedCount.set(0);
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
        this.remainingFailures.set(0);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("remainingFailures", remainingFailures.get());
        m.put("injectedCount", injectedCount.get());
        return m;
    }
}
