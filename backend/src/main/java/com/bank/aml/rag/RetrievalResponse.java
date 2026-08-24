package com.bank.aml.rag;

import java.util.List;

/**
 * 可审计检索输出；状态用于驱动 Agent 拒答、重试或人工复核，{@code support} 给出判定原因，
 * {@code traces} 完整保留每阶段分数（SearchHit）供审计与评测使用。
 */
public final class RetrievalResponse {
    private final Status status;
    private final String indexVersion;
    private final List<RetrievalHit> hits;
    private final EvidenceSupport support;
    private final List<SearchHit> traces;

    public RetrievalResponse(Status status, String indexVersion, List<RetrievalHit> hits) {
        this(status, indexVersion, hits, null, List.of());
    }

    public RetrievalResponse(Status status, String indexVersion, List<RetrievalHit> hits,
                             EvidenceSupport support, List<SearchHit> traces) {
        this.status = status;
        this.indexVersion = indexVersion == null ? "" : indexVersion;
        this.hits = hits == null ? List.of() : List.copyOf(hits);
        this.support = support;
        this.traces = traces == null ? List.of() : List.copyOf(traces);
    }

    public Status status() { return status; }
    public String indexVersion() { return indexVersion; }
    public List<RetrievalHit> hits() { return hits; }
    public EvidenceSupport support() { return support; }
    public List<SearchHit> traces() { return traces; }

    public boolean supported() {
        return status == Status.SUPPORTED;
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