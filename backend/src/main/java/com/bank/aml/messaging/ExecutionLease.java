package com.bank.aml.messaging;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次执行的租约：由 Worker 抢占成功后创建，携带 caseId + executionVersion + workerId。
 * <p>心跳线程发现 {@code updateHeartbeat} 返回 0 时调用 {@link #markLost()}；主流程各阶段检查
 * {@link #isValid()}，租约丢失后不再产生日志 / SSE / 报告等对用户可见的副作用。
 */
public class ExecutionLease {

    private final Long caseId;
    private final int executionVersion;
    private final String workerId;
    private final AtomicBoolean lost = new AtomicBoolean(false);

    public ExecutionLease(Long caseId, int executionVersion, String workerId) {
        this.caseId = caseId;
        this.executionVersion = executionVersion;
        this.workerId = workerId;
    }

    public boolean isValid() {
        return !lost.get();
    }

    public void markLost() {
        lost.set(true);
    }

    public Long caseId() {
        return caseId;
    }

    public int executionVersion() {
        return executionVersion;
    }

    public String workerId() {
        return workerId;
    }
}
