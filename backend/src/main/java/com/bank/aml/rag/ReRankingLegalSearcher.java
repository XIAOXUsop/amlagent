package com.bank.aml.rag;

import com.bank.aml.rag.rerank.BgeRerankerScoringModel;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 召回 + 精排两步走：
 * 1. 混合检索召回 recallSize 条候选；
 * 2. bge-reranker（Cross-Encoder）对 query-候选 精排，返回 topK。
 * <p>rerank 不可用时降级为直接返回召回结果前 topK。
 */
@Component
public class ReRankingLegalSearcher implements LegalDocumentSearcher {

    private final HybridLegalSearcher hybridSearcher;
    private final BgeRerankerScoringModel reranker;
    private final int recallSize;

    public ReRankingLegalSearcher(HybridLegalSearcher hybridSearcher, BgeRerankerScoringModel reranker,
                                  @Value("${aml.rag.rerank.recall-size:20}") int recallSize) {
        this.hybridSearcher = hybridSearcher;
        this.reranker = reranker;
        this.recallSize = recallSize;
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        return rerank(query, topK, hybridSearcher.search(query, recallSize));
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        return rerank(request.query(), topK, hybridSearcher.search(request, recallSize));
    }

    /** 缓存身份反映运行时模型状态、精排窗口和融合参数，而不只依赖人工版本号。 */
    String pipelineIdentity() {
        return "rerank-" + reranker.runtimeIdentity() + "-window" + recallSize + "-" + hybridSearcher.fusionIdentity();
    }

    private List<LegalDoc> rerank(String query, int topK, List<LegalDoc> recalled) {
        if (recalled.isEmpty()) {
            return recalled;
        }
        // 即使候选数等于 topK 也必须重排：上层会为有效期/相关性门控扩大候选集，
        // 若在这里按 size<=topK 跳过，生产配置中的 Cross-Encoder 实际永远不会执行。
        if (!reranker.isAvailable()) {
            return recalled.subList(0, Math.min(topK, recalled.size()));
        }

        List<TextSegment> segments = recalled.stream()
                .map(d -> TextSegment.from(d.content()))
                .toList();
        List<Double> scores = reranker.scoreAll(segments, query).content();

        List<Integer> indices = IntStream.range(0, recalled.size()).boxed().toList();
        List<Integer> ranked = indices.stream()
                .sorted((a, b) -> Double.compare(scores.get(b), scores.get(a)))
                .toList();
        return ranked.subList(0, Math.min(topK, ranked.size())).stream()
                .map(recalled::get)
                .toList();
    }
}
