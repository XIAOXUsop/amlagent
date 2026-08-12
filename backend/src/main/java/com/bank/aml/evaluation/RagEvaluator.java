package com.bank.aml.evaluation;

import com.bank.aml.rag.LegalDoc;
import com.bank.aml.rag.LegalDocumentSearcher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 检索评测：对固定评测集计算 Recall@5 / Top3 命中率 / MRR / P95 耗时。
 */
@Service
public class RagEvaluator {

    private final LegalDocumentSearcher searcher;
    private final RagEvalSetGenerator generator;

    public RagEvaluator(LegalDocumentSearcher searcher, RagEvalSetGenerator generator) {
        this.searcher = searcher;
        this.generator = generator;
    }

    public record PerCase(String question, String expectedEvidenceId, int rank, long durationMs) {
    }

    public record RagEvalReport(
            int totalCases,
            double recallAt5,
            double top3HitRate,
            double mrr,
            double p95DurationMs,
            List<PerCase> details
    ) {
    }

    public RagEvalReport evaluate() {
        List<RagEvalSetGenerator.RagEvalCase> cases = generator.generate();
        List<PerCase> details = new ArrayList<>();
        int recallHit = 0;
        int top3Hit = 0;
        double mrrSum = 0;

        for (RagEvalSetGenerator.RagEvalCase c : cases) {
            long start = System.currentTimeMillis();
            List<LegalDoc> results = searcher.search(c.question(), 5);
            long ms = System.currentTimeMillis() - start;

            int rank = -1;
            for (int i = 0; i < results.size(); i++) {
                if (c.expectedEvidenceId().equals(results.get(i).evidenceId())) {
                    rank = i + 1;
                    break;
                }
            }
            if (rank > 0 && rank <= 5) {
                recallHit++;
            }
            if (rank > 0 && rank <= 3) {
                top3Hit++;
            }
            if (rank > 0) {
                mrrSum += 1.0 / rank;
            }
            details.add(new PerCase(c.question(), c.expectedEvidenceId(), rank, ms));
        }

        int n = cases.size();
        double recallAt5 = n == 0 ? 0 : 100.0 * recallHit / n;
        double top3 = n == 0 ? 0 : 100.0 * top3Hit / n;
        double mrr = n == 0 ? 0 : mrrSum / n;
        double p95 = p95(details.stream().mapToLong(PerCase::durationMs).toArray());

        return new RagEvalReport(n, round1(recallAt5), round1(top3), round1(mrr * 100), p95, details);
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
