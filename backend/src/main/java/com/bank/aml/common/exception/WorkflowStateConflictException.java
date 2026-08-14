package com.bank.aml.common.exception;

import com.bank.aml.common.enums.CaseStatus;

import java.util.Set;

/**
 * 工单状态冲突异常：命令在非允许的源状态下执行时抛出，由全局异常处理映射为 HTTP 409。
 */
public class WorkflowStateConflictException extends RuntimeException {

    private final Long caseId;
    private final CaseStatus actual;
    private final Set<CaseStatus> expected;

    public WorkflowStateConflictException(Long caseId, CaseStatus actual, Set<CaseStatus> expected) {
        super("工单 " + caseId + " 状态冲突：当前=" + actual + "，允许=" + expected);
        this.caseId = caseId;
        this.actual = actual;
        this.expected = expected;
    }

    public Long getCaseId() {
        return caseId;
    }

    public CaseStatus getActual() {
        return actual;
    }

    public Set<CaseStatus> getExpected() {
        return expected;
    }
}
