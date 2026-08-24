package com.bank.aml.evaluation;

import com.bank.aml.observability.MetricsRecorder;
import com.bank.aml.rag.CacheMode;
import com.bank.aml.rag.EnterpriseLegalRetriever;
import com.bank.aml.rag.LegalDoc;
import com.bank.aml.rag.LegalDocumentSearcher;
import com.bank.aml.rag.LegalIndexVersionProvider;
import com.bank.aml.rag.RetrievalPipelineFactory;
import com.bank.aml.rag.RetrievalRequest;
import com.bank.aml.rag.RetrievalResponse;
import com.bank.aml.rag.RetrievalTarget;
import com.bank.aml.rag.RetrievalTimings;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索评测：对评测集计算 Recall@5 / Top3 / MRR / nDCG@5 / 拒答率，并输出冷暖 P50/P95/P99 与分段耗时。
 * <ul>
 *   <li>评测默认绕过 Redis 缓存（{@link CacheMode#BYPASS_READ_WRITE}）；</li>
 *   <li>{@link #evaluatePipeline(RetrievalPipeline)} 对 dense/lexical/hybrid/hybrid+rerank 做 A/B；</li>
 *   <li>{@link #evaluateAdversarial()} 运行 16 类对抗评测集（>=150 条），覆盖越权/失效/投毒/伪造来源/敏感泄漏等 OWASP GenAI 风险。</li>
 * </ul>
 */
@Service
public class RagEvaluator {

    private final EnterpriseLegalRetriever retriever;
    private final RagEvalDatasetLoader datasetLoader;
    private final LegalIndexVersionProvider versions;
    private final MetricsRecorder metrics;
    private final RetrievalPipelineFactory pipelineFactory;
    private final RagAdversarialFixtureFactory fixtureFactory;

    public RagEvaluator(EnterpriseLegalRetriever retriever, RagEvalDatasetLoader datasetLoader) {
        this(retriever, datasetLoader, () -> "", null, null, new RagAdversarialFixtureFactory());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RagEvaluator(EnterpriseLegalRetriever retriever, RagEvalDatasetLoader datasetLoader,
                        LegalIndexVersionProvider versions, MetricsRecorder metrics,
                        RetrievalPipelineFactory pipelineFactory,
                        RagAdversarialFixtureFactory fixtureFactory) {
        this.retriever = retriever;
        this.datasetLoader = datasetLoader;
        this.versions = versions == null ? () -> "" : versions;
        this.metrics = metrics;
        this.pipelineFactory = pipelineFactory;
        this.fixtureFactory = fixtureFactory == null ? new RagAdversarialFixtureFactory() : fixtureFactory;
    }

    public record PerCase(String id, String question, boolean answerable, int rank,
                          boolean abstained, long durationMs, String retrievalStatus,
                          String indexVersion, List<String> returnedEvidenceIds,
                          List<Double> relevanceScores, long coldMs, long warmMs,
                          Map<String, Long> segmentedLatencyMs, String support, String category,
                          boolean fixtureApplied, boolean fixtureExpectationMatched) {
    }

    /** 各分段平均耗时（ms） */
    public record SegmentedLatency(Map<String, Double> averageMs) {
        public static final SegmentedLatency EMPTY = new SegmentedLatency(Map.of());
    }

    public record RagEvalReport(
            int totalCases,
            double recallAt5,
            double top3HitRate,
            double mrr,
            double ndcgAt5,
            double abstentionAccuracy,
            double noAnswerRefusalRate,
            double p95DurationMs,
            double coldP50Ms,
            double coldP95Ms,
            double coldP99Ms,
            double warmP50Ms,
            double warmP95Ms,
            double warmP99Ms,
            SegmentedLatency segmentedMs,
            String datasetVersion,
            String datasetHash,
            String reviewStatus,
            List<PerCase> details,
            String pipeline
    ) {
    }

    /** 当前生效索引评测（生产管线，语义上绕过缓存）。 */
    public RagEvalReport evaluate() {
        return run(datasetLoader.dataset(), datasetLoader.datasetHash(),
                RetrievalTarget.ACTIVE, null, CacheMode.BYPASS_READ_WRITE, null, "PRODUCTION");
    }

    /** 候选/指定版本评测：显式版本身份 + 绕过缓存，供候选发布门禁使用。 */
    public RagEvalReport evaluateCandidate(String version) {
        return run(datasetLoader.dataset(), datasetLoader.datasetHash(),
                RetrievalTarget.SPECIFIC_VERSION, version, CacheMode.BYPASS_READ_WRITE, null, "PRODUCTION");
    }

    /** 对指定检索管线进行 A/B 评测（dense/lexical/hybrid/hybrid+rerank）。 */
    public RagEvalReport evaluatePipeline(RetrievalPipeline pipeline) {
        return run(datasetLoader.dataset(), datasetLoader.datasetHash(),
                RetrievalTarget.ACTIVE, null, CacheMode.BYPASS_READ_WRITE, pipeline, pipeline.name());
    }

    /** 对抗性评测集回归（OWASP GenAI 风险类别）。 */
    public RagEvalReport evaluateAdversarial() {
        return run(datasetLoader.adversarialDataset(), datasetLoader.adversarialHash(),
                RetrievalTarget.ACTIVE, null, CacheMode.BYPASS_READ_WRITE, null, "ADVERSARIAL");
    }

    private RagEvalReport run(RagEvalDataset dataset, String datasetHash,
                              RetrievalTarget target, String specificVersion, CacheMode cacheMode,
                              RetrievalPipeline pipeline, String pipelineLabel) {
        EnterpriseLegalRetriever activeRetriever = effectiveRetriever(pipeline);
        List<RagEvalDataset.RagEvalCase> cases = dataset.cases();
        List<PerCase> details = new ArrayList<>();
        int recallHit = 0;
        int top3Hit = 0;
        double mrrSum = 0;
        double ndcgSum = 0;
        int abstentionCorrect = 0;
        int noAnswerRefused = 0;
        List<Double> coldTimes = new ArrayList<>();
        List<Double> warmTimes = new ArrayList<>();
        Map<String, Long> segmentedSum = new LinkedHashMap<>();
        int segmentedCount = 0;

        for (RagEvalDataset.RagEvalCase c : cases) {
            RetrievalRequest request = new RetrievalRequest(c.question(), c.question(),
                    java.time.Instant.parse("2026-08-01T00:00:00Z"), "CN",
                    java.util.Set.of("PUBLIC_LEGAL"), 5, 0.04, target, specificVersion, cacheMode);
            String requestedFixtureVersion = specificVersion != null ? specificVersion : versions.activeVersion();
            String fixtureVersion = requestedFixtureVersion == null || requestedFixtureVersion.isBlank()
                    ? "adversarial-fixture-v1" : requestedFixtureVersion;
            var fixture = "ADVERSARIAL".equals(pipelineLabel)
                    ? fixtureFactory.scenario(c, fixtureVersion) : java.util.Optional.<RagAdversarialFixtureFactory.Scenario>empty();
            EnterpriseLegalRetriever caseRetriever = fixture
                    .map(scenario -> fixtureRetriever(scenario, fixtureVersion))
                    .orElse(activeRetriever);
            RetrievalTimings.reset();
            long coldStart = System.nanoTime();
            RetrievalResponse cold = caseRetriever.retrieve(request);
            long coldMs = (System.nanoTime() - coldStart) / 1_000_000;
            Map<String, Long> segmented = RetrievalTimings.drain();
            RetrievalTimings.reset();
            long warmStart = System.nanoTime();
            caseRetriever.retrieve(request);
            long warmMs = (System.nanoTime() - warmStart) / 1_000_000;
            RetrievalTimings.drain();
            if (metrics != null) {
                metrics.ragLatency("cold", coldMs);
                metrics.ragLatency("warm", warmMs);
            }

            List<LegalDoc> results = cold.hits().stream().map(RetrievalResponse.RetrievalHit::document).toList();
            int rank = -1;
            if (c.answerable()) for (int i = 0; i < results.size(); i++) {
                LegalDoc doc = results.get(i);
                if (contains(doc.title(), c.expectedTitleContains())
                        && contains(doc.content(), c.expectedContentContains())) {
                    rank = i + 1;
                    break;
                }
            }
            boolean abstained = cold.status() != RetrievalResponse.Status.SUPPORTED;
            if ((!c.answerable() && abstained) || (c.answerable() && !abstained)) abstentionCorrect++;
            if (!c.answerable() && abstained) noAnswerRefused++;
            if (rank > 0 && rank <= 5) recallHit++;
            if (rank > 0 && rank <= 3) top3Hit++;
            if (rank > 0) {
                mrrSum += 1.0 / rank;
                ndcgSum += 1.0 / (Math.log(rank + 1) / Math.log(2));
            }
            coldTimes.add((double) coldMs);
            warmTimes.add((double) warmMs);
            for (Map.Entry<String, Long> entry : segmented.entrySet()) {
                segmentedSum.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
            if (!segmented.isEmpty()) segmentedCount++;
            details.add(new PerCase(c.id(), c.question(), c.answerable(), rank, abstained, coldMs,
                    cold.status().name(), cold.indexVersion(),
                    cold.hits().stream().map(hit -> hit.document().evidenceId()).toList(),
                    cold.hits().stream().map(RetrievalResponse.RetrievalHit::relevanceScore).toList(),
                    coldMs, warmMs, segmented, cold.support() == null ? "" : cold.support().name(),
                    c.category(), fixture.isPresent(), fixture.isEmpty()
                            || fixture.get().expectedSupport() == cold.support()));
        }

        int n = cases.size();
        long answerableCount = cases.stream().filter(RagEvalDataset.RagEvalCase::answerable).count();
        double recallAt5 = answerableCount == 0 ? 0 : 100.0 * recallHit / answerableCount;
        double top3 = answerableCount == 0 ? 0 : 100.0 * top3Hit / answerableCount;
        double mrr = answerableCount == 0 ? 0 : mrrSum / answerableCount;
        double ndcg = answerableCount == 0 ? 0 : ndcgSum / answerableCount;
        double abstention = n == 0 ? 0 : 100.0 * abstentionCorrect / n;
        long noAnswerCount = cases.stream().filter(c -> !c.answerable()).count();
        double noAnswerRefusal = noAnswerCount == 0 ? 0 : 100.0 * noAnswerRefused / noAnswerCount;

        double[] cold = coldTimes.stream().mapToDouble(Double::doubleValue).toArray();
        double[] warm = warmTimes.stream().mapToDouble(Double::doubleValue).toArray();
        Map<String, Double> segmentedAverage = new LinkedHashMap<>();
        if (segmentedCount > 0) {
            for (Map.Entry<String, Long> entry : segmentedSum.entrySet()) {
                segmentedAverage.put(entry.getKey(), round1(entry.getValue() / (double) segmentedCount));
            }
        }

        return new RagEvalReport(n, round1(recallAt5), round1(top3), round1(mrr * 100),
                round1(ndcg * 100), round1(abstention), round1(noAnswerRefusal),
                percentile(cold, 0.95), percentile(cold, 0.50), percentile(cold, 0.95), percentile(cold, 0.99),
                percentile(warm, 0.50), percentile(warm, 0.95), percentile(warm, 0.99),
                new SegmentedLatency(Map.copyOf(segmentedAverage)),
                dataset.datasetVersion(), datasetHash, dataset.reviewStatus(), List.copyOf(details),
                pipelineLabel);
    }

    private EnterpriseLegalRetriever effectiveRetriever(RetrievalPipeline pipeline) {
        if (pipeline == null || pipelineFactory == null) {
            return retriever;
        }
        LegalDocumentSearcher searcher = pipelineFactory.build(pipeline);
        io.micrometer.core.instrument.MeterRegistry registry = metrics != null ? null : meterRegistry();
        return new EnterpriseLegalRetriever(searcher, versions,
                metrics != null ? metrics : new MetricsRecorder(registry), 4);
    }

    private EnterpriseLegalRetriever fixtureRetriever(RagAdversarialFixtureFactory.Scenario scenario,
                                                       String fixtureVersion) {
        LegalIndexVersionProvider fixtureVersions = () -> fixtureVersion;
        MetricsRecorder recorder = metrics != null ? metrics : new MetricsRecorder(meterRegistry());
        return new EnterpriseLegalRetriever(scenario.searcher(), fixtureVersions, recorder, 4);
    }

    private io.micrometer.core.instrument.MeterRegistry meterRegistry() {
        return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    private boolean contains(String actual, String expected) {
        return actual != null && expected != null && actual.contains(expected);
    }

    private double percentile(double[] values, double quantile) {
        if (values.length == 0) return 0;
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(quantile * sorted.length) - 1;
        return round1(sorted[Math.max(0, Math.min(idx, sorted.length - 1))]);
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
