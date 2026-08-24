package com.bank.aml.evaluation;

/**
 * 检索管线可选项，用于 A/B 评测（不直接替换生产管线）。
 * <ul>
 *   <li>{@link #DENSE}：仅向量语义召回；</li>
 *   <li>{@link #LEXICAL}：仅字段化关键词召回；</li>
 *   <li>{@link #HYBRID}：向量 + 关键词 RRF 融合；</li>
 *   <li>{@link #HYBRID_RERANK}：混合召回 + Reranker 精排（生产默认）。</li>
 * </ul>
 */
public enum RetrievalPipeline {
    DENSE,
    LEXICAL,
    HYBRID,
    HYBRID_RERANK
}