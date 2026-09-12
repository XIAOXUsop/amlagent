package com.bank.aml.explanation;

/**
 * 六问题的人工评估状态（v2 计划 §5）。 状态由人提出，服务端只检查其适用条件和证据约束；系统不能独立证明人工判断真实。
 */
public enum QuestionAssessment {

    SATISFIED, NOT_SATISFIED, UNKNOWN, NOT_APPLICABLE

}
