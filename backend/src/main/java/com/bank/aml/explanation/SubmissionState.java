package com.bank.aml.explanation;

/** 提交适用性状态（v2 计划 §9）：不修改已冻结 payload；最终决定只能采用 CURRENT。 */
public enum SubmissionState {

    CURRENT,
    /** 分析员显式撤回（amendment）。仍可回放，但不能被最终决定采用。 */
    WITHDRAWN,
    /** 引用材料/核验变化使其依据失效（V2-11）。 */
    STALE,
    /** 被后续提交接替。 */
    SUPERSEDED

}
