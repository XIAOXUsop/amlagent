package com.bank.aml.explanation;

/** 问题重要性等级（v2 计划 §6.1）；未评估默认按 DECISION_CRITICAL 待处理。 */
public enum IssueSeverity {
    /** 完整性/身份错误：不得以被破坏的依据形成任何最终决定；不允许降级。 */
    INTEGRITY_BLOCKER,
    /** 关键业务矛盾：阻断合理排除；可作人工怀疑分析的一部分，不能假称已解决。 */
    DECISION_CRITICAL,
    /** 非关键缺口：只有明确解释“不影响本次决定”的理由和引用时可继续。 */
    CONTEXT_GAP,
    /** 尚未到期事项：合同约定未来验收、预付款到货复核；需分派持续跟进。 */
    FUTURE_OBLIGATION
}
