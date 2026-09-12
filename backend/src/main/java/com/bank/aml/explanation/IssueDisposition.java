package com.bank.aml.explanation;

/** 问题处置闭集（v2 计划 §6.1）：撤销任务与解决问题分开；新文件到达不自动关闭问题。 */
public enum IssueDisposition {

    OPEN,
    /** 用具体证据解决问题（记录证据定位与核验方法）。 */
    RESOLVED_WITH_EVIDENCE,
    /** 说明与本次决定不相关的理由和引用后关闭。 */
    NOT_RELEVANT_WITH_REASON,
    /** 显式披露为未解决；只允许在确认可疑路径中被采用，不得改写为 EXPLAINED。 */
    DISCLOSED_UNRESOLVED

}
