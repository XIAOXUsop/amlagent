package com.bank.aml.explanation;

/**
 * 解释方向的证据定位（v2 计划 §12）。 与风险假设证据的 SUPPORTS/CONTRADICTS 方向不混用。
 */
public enum ExplanationEvidenceDirection {

    SUPPORTS_EXPLANATION, CHALLENGES_EXPLANATION, CONTEXT

}
