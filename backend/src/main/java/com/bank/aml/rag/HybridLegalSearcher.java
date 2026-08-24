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

    private final VectorLegalSearcher vectorLegalSearcher;
    private final KeywordLegalSearcher keywordLegalSearcher;
    private final int rrfK;
    private final double vectorWeight;
    private final double keywordWeight;

    public HybridLegalSearcher(VectorLegalSearcher vectorLegalSearcher, KeywordLegalSearcher keywordLegalSearcher) {
        this(vectorLegalSearcher, keywordLegalSearcher, 60, 1.0, 1.0);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HybridLegalSearcher(VectorLegalSearcher vectorLegalSearcher, KeywordLegalSearcher keywordLegalSearcher,
                               @org.springframework.beans.factory.annotation.Value("${aml.rag.fusion.rrf-k:60}") int rrfK,
                               @org.springframework.beans.factory.annotation.Value("${aml.rag.fusion.vector-weight:1.0}") double vectorWeight,
                               @org.springframework.beans.factory.annotation.Value("${aml.rag.fusion.keyword-weight:1.2}") double keywordWeight) {
        this.vectorLegalSearcher = vectorLegalSearcher;
        this.keywordLegalSearcher = keywordLegalSearcher;
        this.rrfK = Math.max(1, rrfK);
        this.vectorWeight = Math.max(0, vectorWeight);
        this.keywordWeight = Math.max(0, keywordWeight);
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        return fuseResults(vectorLegalSearcher.search(query, Math.max(topK * 3, 10)),
                keywordLegalSearcher.search(query, Math.max(topK * 3, 10)), topK);
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        int recall = Math.max(topK * 3, 10);
        return fuseResults(vectorLegalSearcher.search(request, recall), keywordLegalSearcher.search(request, recall), topK);
    }

    String fusionIdentity() {
        return "rrf" + rrfK + "-vw" + vectorWeight + "-kw" + keywordWeight;
    }

    private List<LegalDoc> fuseResults(List<LegalDoc> vectorHits, List<LegalDoc> keywordHits, int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, LegalDoc> byId = new HashMap<>();
        fuse(rrfScores, byId, vectorHits, vectorWeight);
        fuse(rrfScores, byId, keywordHits, keywordWeight);

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()))
                .toList();
    }

    private void fuse(Map<String, Double> scores, Map<String, LegalDoc> byId, List<LegalDoc> docs, double weight) {
        for (int rank = 0; rank < docs.size(); rank++) {
            LegalDoc doc = docs.get(rank);
            if (doc.evidenceId() == null || doc.evidenceId().isEmpty()) {
                continue;
            }
            scores.merge(doc.evidenceId(), weight / (rrfK + rank + 1), Double::sum);
            byId.put(doc.evidenceId(), doc);
        }
    }
}
