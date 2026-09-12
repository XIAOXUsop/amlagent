package com.bank.aml.explanation;

/** 预警核验单元的人工建议（v2 计划 §7.1）：解释不成立本身不足以推出 SUSPICIOUS。 */
public enum ExplanationOutcome {

    /** 适用配方满足、范围完整、解释有已记录的适用核验、关键矛盾已处理。 */
    EXPLAINED,
    /** 分析员明确记录支持合理怀疑的已评估事实、反向解释及为何仍不足以消除疑点。 */
    SUSPICIOUS,
    /** 尚无充分解释，也未形成足够的人工怀疑分析；保留未知，不二选一凑结论。 */
    UNRESOLVED

}
