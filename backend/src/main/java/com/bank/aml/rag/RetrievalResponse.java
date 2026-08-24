package com.bank.aml.rag;

import java.util.List;

/** 可审计检索输出；状态用于驱动 Agent 拒答、重试或人工复核。 */
public record RetrievalResponse(
        Status status,
        String indexVersion,
        List<RetrievalHit> hits
) {
    public RetrievalResponse {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    public enum Status {
        SUPPORTED,
        INSUFFICIENT_EVIDENCE,
        NO_RELEVANT_EVIDENCE,
        INDEX_UNAVAILABLE,
        ACCESS_DENIED
    }

    public record RetrievalHit(LegalDoc document, int finalRank, double relevanceScore) {
    }
}
