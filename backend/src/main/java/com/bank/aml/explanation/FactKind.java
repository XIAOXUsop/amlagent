package com.bank.aml.explanation;

/**
 * 事实、陈述、推断与未知四类内容（v2 计划 §5.1）。 不允许模型把 DOCUMENT_ASSERTION 升级成 OBSERVED_FACT。
 */
public enum FactKind {

    /** 文件记载（如“合同称已发货”）。 */
    DOCUMENT_ASSERTION,
    /** 核验人在明确系统/方式中观察到的事实。 */
    OBSERVED_FACT,
    /** 分析员据此形成的业务推断。 */
    ANALYST_INFERENCE,
    /** 尚未核实。 */
    UNKNOWN

}
