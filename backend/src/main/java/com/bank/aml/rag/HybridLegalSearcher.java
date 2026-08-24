package com.bank.aml.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索：向量语义召回 + 关键词召回，通过 Reciprocal Rank Fusion（RRF）融合排序。
 * <p>基于 {@link SearchHit} 融合，保留各阶段分数、命中的 DENSE/LEXICAL 通道与命中原因。</p>
 */
@Component
public class HybridLegalSearcher implements LegalDocumentSearcher {

    private final VectorLegalSearcher vectorLegalSearcher;
    private final KeywordLegalSearcher keywordLegalSearcher;
    private final int rrfK;
    private final double vectorWeight;
    private final double keywordWeight;
    private final double lexicalScoreBonus;

    public HybridLegalSearcher(VectorLegalSearcher vectorLegalSearcher, KeywordLegalSearcher keywordLegalSearcher) {
        this(vectorLegalSearcher, keywordLegalSearcher, 60, 1.0, 1.0, 0.08);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public HybridLegalSearcher(VectorLegalSearcher vectorLegalSearcher, KeywordLegalSearcher keywordLegalSearcher,
                               @org.springframework.beans.factory.annotation.Value("${aml.rag.fusion.rrf-k:60}") int rrfK,
                               @org.springframework.beans.factory.annotation.Value("${aml.rag.fusion.vector-weight:1.0}") double vectorWeight,
                               @org.springframework.beans.factory.annotation.Value("${aml.rag.fusion.keyword-weight:1.2}") double keywordWeight,
                               @org.springframework.beans.factory.annotation.Value("${aml.rag.fusion.lexical-score-bonus:0.08}") double lexicalScoreBonus) {
        this.vectorLegalSearcher = vectorLegalSearcher;
        this.keywordLegalSearcher = keywordLegalSearcher;
        this.rrfK = Math.max(1, rrfK);
        this.vectorWeight = Math.max(0, vectorWeight);
        this.keywordWeight = Math.max(0, keywordWeight);
        this.lexicalScoreBonus = Math.max(0, Math.min(0.25, lexicalScoreBonus));
    }

    @Override
    public List<SearchHit> searchScored(RetrievalRequest request, int topK) {
        int recall = Math.max(topK * 3, 10);
        return fuseHits(vectorLegalSearcher.searchScored(request, recall),
                keywordLegalSearcher.searchScored(request, recall), topK);
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        return searchScored(request, topK).stream().map(SearchHit::document).toList();
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        return searchScored(new RetrievalRequest(query, query, java.time.Instant.now(),
                "CN", Set.of("PUBLIC_LEGAL"), topK, 0.0), topK)
                .stream().map(SearchHit::document).toList();
    }

    String fusionIdentity() {
        return "rrf" + rrfK + "-vw" + vectorWeight + "-kw" + keywordWeight;
    }

    private List<SearchHit> fuseHits(List<SearchHit> dense, List<SearchHit> lexical, int topK) {
        long started = System.nanoTime();
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, SearchHit> byId = new HashMap<>();
        fuse(rrfScores, byId, dense, vectorWeight);
        fuse(rrfScores, byId, lexical, keywordWeight);

        List<SearchHit> result = new ArrayList<>();
        List<Map.Entry<String, Double>> ranking = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList();
        for (Map.Entry<String, Double> entry : ranking) {
            if (result.size() >= topK) break;
            SearchHit hit = byId.get(entry.getKey());
            Set<RetrievalChannel> channels = new LinkedHashSet<>(hit.channels());
            channels.add(RetrievalChannel.FUSION);
            List<String> reasons = new ArrayList<>(hit.matchReasons());
            result.add(hit.fused(entry.getValue(), channels, reasons));
        }
        RetrievalTimings.add("fusion", Math.max(0, (System.nanoTime() - started) / 1_000_000));
        return result;
    }

    private void fuse(Map<String, Double> scores, Map<String, SearchHit> byId, List<SearchHit> docs, double weight) {
        for (int rank = 0; rank < docs.size(); rank++) {
            SearchHit hit = docs.get(rank);
            if (hit.document().evidenceId() == null || hit.document().evidenceId().isEmpty()) {
                continue;
            }
            scores.merge(hit.document().evidenceId(), weight / (rrfK + rank + 1), Double::sum);
            // RRF 只看名次，在小语料/弱中文向量模型下会让“字段精确命中但 dense 名次稍后”的条款被淹没。
            // 将字段加权词法分映射为有上界的小额 bonus，保留 RRF 稳定性的同时尊重可解释的精确匹配强度。
            if (hit.lexicalScore() != null && hit.lexicalScore() > 0) {
                double boundedLexical = hit.lexicalScore() / (hit.lexicalScore() + 5.0);
                scores.merge(hit.document().evidenceId(), keywordWeight * lexicalScoreBonus * boundedLexical,
                        Double::sum);
            }
            byId.merge(hit.document().evidenceId(), hit, SearchHit::mergeRecall);
        }
    }
}
