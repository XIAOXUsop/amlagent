package com.bank.aml.rag.rerank;

/**
 * Reranker 熔断状态机：CLOSED（正常）→ OPEN（连续失败熔断）→ HALF_OPEN（冷却后单探测）→ CLOSED。
 */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}