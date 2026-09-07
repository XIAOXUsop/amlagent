package com.bank.aml.explanation;

/**
 * 补充尽调任务目的（v2 计划 §8.2）。
 * 存量数据（v2 之前创建的任务）按 DECISION_SUPPORT 语义处理。
 */
public enum EddTaskPurpose {
    /** 当前判断需要的补件：OPEN 时阻断最终处置。 */
    DECISION_SUPPORT,
    /** 已分派、可在本轮决定后履行的未来核验或已披露未知的继续调查。 */
    CONTINUING_REVIEW
}
