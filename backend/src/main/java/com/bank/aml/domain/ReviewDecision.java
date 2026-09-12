package com.bank.aml.domain;

import java.util.Locale;

/** 人工分析后的业务处置，不再使用含糊的“批准/驳回”表达。 */
public enum ReviewDecision {

    /** 确认存在可疑特征，案件完成分析并进入后续可疑交易报告流程。 */
    CONFIRM_SUSPICIOUS,
    /** 经核验有合理业务解释，排除本次预警。 */
    EXCLUDE_FALSE_POSITIVE,
    /** 当前证据不足，保持待复核并发起强化尽调。 */
    REQUEST_ENHANCED_DUE_DILIGENCE;

    public static ReviewDecision parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请选择处置结论（确认可疑 / 排除预警 / 补充尽调）");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException invalidDecision) {
            throw new IllegalArgumentException("非法处置结论：" + value);
        }
    }

}
