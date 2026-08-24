package com.bank.aml.rag;

import com.bank.aml.evaluation.RetrievalPipeline;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按检索管线产出对应的 {@link LegalDocumentSearcher}，供 A/B 评测对比。
 * <p>生产检索仍然走 {@link CachingLegalSearcher}（默认 HYBRID_RERANK），此工厂仅用于评测实验。</p>
 */
@Component
public class RetrievalPipelineFactory {

    private final VectorLegalSearcher vector;
    private final KeywordLegalSearcher keyword;
    private final HybridLegalSearcher hybrid;
    private final ReRankingLegalSearcher reranking;

    public RetrievalPipelineFactory(VectorLegalSearcher vector, KeywordLegalSearcher keyword,
                                    HybridLegalSearcher hybrid, ReRankingLegalSearcher reranking) {
        this.vector = vector;
        this.keyword = keyword;
        this.hybrid = hybrid;
        this.reranking = reranking;
    }

    public LegalDocumentSearcher build(RetrievalPipeline pipeline) {
        LegalDocumentSearcher delegate = switch (pipeline) {
            case DENSE -> vector;
            case LEXICAL -> keyword;
            case HYBRID -> hybrid;
            case HYBRID_RERANK -> reranking;
        };
        return new LegalDocumentSearcher() {
            @Override
            public List<LegalDoc> search(String query, int topK) {
                return delegate.search(query, topK);
            }

            @Override
            public List<SearchHit> searchScored(RetrievalRequest request, int topK) {
                return delegate.searchScored(request, topK);
            }
        };
    }
}