package com.bank.aml.rag;

/** 检索命中来源通道：向量召回 / 关键词召回 / RRF 融合 / Reranker 精排。 */
public enum RetrievalChannel {
    DENSE,
    LEXICAL,
    FUSION,
    RERANK
}