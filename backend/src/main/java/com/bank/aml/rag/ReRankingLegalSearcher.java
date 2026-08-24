package com.bank.aml.rag;

import com.bank.aml.rag.rerank.BgeRerankerScoringModel;
import com.bank.aml.observability.MetricsRecorder;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 召回 + 精排两步走：
 * 1. 混合检索召回 recallSize 条候选（SearchHit 保留 dense/lexical/fusion 分数）；
 * 2. bge-reranker（Cross-Encoder）对 query-候选 精排，写入 rerankScore 后返回 topK。
 * <p>rerank 状态机判定不可用时（熔断/超时/队列拥塞）保持召回原序，不阻塞检索。</p>
 */
@Component
public class ReRankingLegalSearcher implements LegalDocumentSearcher {

    private final HybridLegalSearcher hybridSearcher;
    private final BgeRerankerScoringModel reranker;
    private final int recallSize;
    private final MetricsRecorder metrics;

    public ReRankingLegalSearcher(HybridLegalSearcher hybridSearcher, BgeRerankerScoringModel reranker,
                                  @Value("${aml.rag.rerank.recall-size:20}") int recallSize) {
        this(hybridSearcher, reranker, recallSize, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReRankingLegalSearcher(HybridLegalSearcher hybridSearcher, BgeRerankerScoringModel reranker,
                                  @Value("${aml.rag.rerank.recall-size:20}") int recallSize,
                                  MetricsRecorder metrics) {
        this.hybridSearcher = hybridSearcher;
        this.reranker = reranker;
        this.recallSize = recallSize;
        this.metrics = metrics;
    }

    @Override
    public List<SearchHit> searchScored(RetrievalRequest request, int topK) {
        return rerank(request.query(), topK, hybridSearcher.searchScored(request, recallSize));
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        return searchScored(request, topK).stream().map(SearchHit::document).toList();
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        var request = new RetrievalRequest(query, query, Instant.now(), "CN", Set.of("PUBLIC_LEGAL"),
                Math.max(1, topK), 0.0);
        return searchScored(request, topK).stream().map(SearchHit::document).toList();
    }

    /** 缓存身份反映运行时模型状态、精排窗口和融合参数，而不只依赖人工版本号。 */
    String pipelineIdentity() {
        return "rerank-" + reranker.runtimeIdentity() + "-window" + recallSize + "-" + hybridSearcher.fusionIdentity();
    }

    private List<SearchHit> rerank(String query, int topK, List<SearchHit> recalled) {
        if (recalled.isEmpty()) {
            return recalled;
        }
        List<TextSegment> segments = recalled.stream()
                .map(hit -> TextSegment.from(hit.document().content()))
                .toList();
        long started = System.nanoTime();
        // 可空推理：状态机决定是否可用；不可用时保持召回原序，不预先 isAvailable() 分流。
        Optional<List<Double>> maybeScores = reranker.tryScoreAll(segments, query);
        RetrievalTimings.add("rerank", Math.max(0, (System.nanoTime() - started) / 1_000_000));
        if (maybeScores.isEmpty()) {
            if (metrics != null) metrics.ragRerankerFallback();
            return recalled.subList(0, Math.min(topK, recalled.size()));
        }

        List<Double> scores = maybeScores.get();
        List<Integer> indices = IntStream.range(0, recalled.size()).boxed().toList();
        List<Integer> ranked = indices.stream()
                .sorted((a, b) -> Double.compare(scores.get(b), scores.get(a)))
                .toList();
        List<SearchHit> result = new java.util.ArrayList<>();
        int order = 1;
        for (int index : ranked.subList(0, Math.min(topK, ranked.size()))) {
            result.add(recalled.get(index).reranked(order++, scores.get(index)));
        }
        return result;
    }
}