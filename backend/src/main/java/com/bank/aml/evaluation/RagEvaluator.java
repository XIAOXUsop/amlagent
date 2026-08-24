package com.bank.aml.evaluation;

import com.bank.aml.rag.LegalDoc;
import com.bank.aml.rag.EnterpriseLegalRetriever;
import com.bank.aml.rag.RetrievalRequest;
import com.bank.aml.rag.RetrievalResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 检索评测：对固定评测集计算 Recall@5 / Top3 命中率 / MRR / P95 耗时。
 */
@Service
public class RagEvaluator {

    private final EnterpriseLegalRetriever retriever;
    private final RagEvalDatasetLoader datasetLoader;

    public RagEvaluator(EnterpriseLegalRetriever retriever, RagEvalDatasetLoader datasetLoader) {
        this.retriever = retriever;
        this.datasetLoader = datasetLoader;
    }

    public record PerCase(String id, String question, boolean answerable, int rank,
                          boolean abstained, long durationMs, String retrievalStatus,
                          String indexVersion, List<String> returnedEvidenceIds,
                          List<Double> relevanceScores) {
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
            String datasetVersion,
            String datasetHash,
            String reviewStatus,
            List<PerCase> details
    ) {
    }

    public RagEvalReport evaluate() {
        RagEvalDataset dataset = datasetLoader.dataset();
        List<RagEvalDataset.RagEvalCase> cases = dataset.cases();
        List<PerCase> details = new ArrayList<>();
        int recallHit = 0;
        int top3Hit = 0;
        double mrrSum = 0;
        double ndcgSum = 0;
        int abstentionCorrect = 0;
        int noAnswerRefused = 0;

        for (RagEvalDataset.RagEvalCase c : cases) {
            long start = System.currentTimeMillis();
            RetrievalResponse retrieval = retriever.retrieve(new RetrievalRequest(c.question(), c.question(),
                    java.time.Instant.parse("2026-08-01T00:00:00Z"), "CN",
                    java.util.Set.of("PUBLIC_LEGAL"), 5, 0.04));
            List<LegalDoc> results = retrieval.hits().stream().map(RetrievalResponse.RetrievalHit::document).toList();
            long ms = System.currentTimeMillis() - start;

            int rank = -1;
            if (c.answerable()) for (int i = 0; i < results.size(); i++) {
                LegalDoc doc = results.get(i);
                if (contains(doc.title(), c.expectedTitleContains())
                        && contains(doc.content(), c.expectedContentContains())) {
                    rank = i + 1;
                    break;
                }
            }
            boolean abstained = retrieval.status() != RetrievalResponse.Status.SUPPORTED;
            if ((!c.answerable() && abstained) || (c.answerable() && !abstained)) abstentionCorrect++;
            if (!c.answerable() && abstained) noAnswerRefused++;
            if (rank > 0 && rank <= 5) {
                recallHit++;
            }
            if (rank > 0 && rank <= 3) {
                top3Hit++;
            }
            if (rank > 0) {
                mrrSum += 1.0 / rank;
                ndcgSum += 1.0 / (Math.log(rank + 1) / Math.log(2));
            }
            details.add(new PerCase(c.id(), c.question(), c.answerable(), rank, abstained, ms,
                    retrieval.status().name(), retrieval.indexVersion(),
                    retrieval.hits().stream().map(hit -> hit.document().evidenceId()).toList(),
                    retrieval.hits().stream().map(RetrievalResponse.RetrievalHit::relevanceScore).toList()));
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
        double p95 = p95(details.stream().mapToLong(PerCase::durationMs).toArray());

        return new RagEvalReport(n, round1(recallAt5), round1(top3), round1(mrr * 100),
                round1(ndcg * 100), round1(abstention), round1(noAnswerRefusal), p95,
                dataset.datasetVersion(), datasetLoader.datasetHash(), dataset.reviewStatus(), details);
    }

    private boolean contains(String actual, String expected) {
        return actual != null && expected != null && actual.contains(expected);
    }

    private double p95(long[] values) {
        if (values.length == 0) {
            return 0;
        }
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(0.95 * sorted.length) - 1;
        return sorted[Math.max(0, idx)];
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
