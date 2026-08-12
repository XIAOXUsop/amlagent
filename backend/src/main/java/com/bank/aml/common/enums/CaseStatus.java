package com.bank.aml.common.enums;

/**
 * 工单生命周期状态。
 */
public enum CaseStatus {
    /** 待处理（已创建，任务待执行） */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 已完成 */
    DONE,
    /** 转人工复核（命中底线规则，如一级制裁） */
    HOLD,
    /** 执行失败 */
    FAILED
}
