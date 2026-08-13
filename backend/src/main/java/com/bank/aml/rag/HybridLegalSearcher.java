package com.bank.aml.rag;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索：向量语义召回 + 关键词召回，通过 Reciprocal Rank Fusion（RRF）融合排序。
 * <p>结果按 evidenceId 去重合并，返回 Top-K 法规证据。
 */
@Component
public class HybridLegalSearcher implements LegalDocumentSearcher {

    private static final int RRF_K = 60;

    private final VectorLegalSearcher vectorLegalSearcher;
    private final KeywordLegalSearcher keywordLegalSearcher;

    public HybridLegalSearcher(VectorLegalSearcher vectorLegalSearcher, KeywordLegalSearcher keywordLegalSearcher) {
        this.vectorLegalSearcher = vectorLegalSearcher;
        this.keywordLegalSearcher = keywordLegalSearcher;
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        int recall = Math.max(topK * 3, 10);
        List<LegalDoc> vectorHits = vectorLegalSearcher.search(query, recall);
        List<LegalDoc> keywordHits = keywordLegalSearcher.search(query, recall);

        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, LegalDoc> byId = new HashMap<>();
        fuse(rrfScores, byId, vectorHits);
        fuse(rrfScores, byId, keywordHits);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()))
                .toList();
    }

    private void fuse(Map<String, Double> scores, Map<String, LegalDoc> byId, List<LegalDoc> docs) {
        for (int rank = 0; rank < docs.size(); rank++) {
            LegalDoc doc = docs.get(rank);
            if (doc.evidenceId() == null || doc.evidenceId().isEmpty()) {
                continue;
            }
            scores.merge(doc.evidenceId(), 1.0 / (RRF_K + rank + 1), Double::sum);
            byId.put(doc.evidenceId(), doc);
        }
    }
}
