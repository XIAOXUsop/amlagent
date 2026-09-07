package com.bank.aml.review;

/** 补充尽调任务生命周期。 */
public enum EnhancedDueDiligenceStatus {
    /** 等待分析员补充材料。 */
    OPEN,
    /** 材料已提交，等待复核员重新判断。 */
    SUBMITTED,
    /** 已被后续人工处置消费并归档。 */
    RESOLVED,
    /** 误发或不再需要，由复核员撤销并保留原因。 */
    CANCELLED
}
