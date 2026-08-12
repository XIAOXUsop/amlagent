package com.bank.aml.rag;

import java.util.List;

/**
 * 法规文档检索接口。
 * <p>Phase 2 由 {@code KeywordLegalSearcher}（关键词匹配）实现；
 * Phase 3 切换为基于 PGVector 的向量检索实现（RAG），工具层无需改动。
 */
public interface LegalDocumentSearcher {

    /** 检索与 query 相关的法规条文，返回 topK 条 */
    List<LegalDoc> search(String query, int topK);
}
