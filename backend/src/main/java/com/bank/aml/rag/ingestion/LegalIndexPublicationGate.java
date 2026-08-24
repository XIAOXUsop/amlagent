package com.bank.aml.rag.ingestion;

import com.bank.aml.evaluation.RagEvaluator;
import com.bank.aml.rag.LegalIndexVersionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选索引发布门禁：只有全部约束通过才允许 {@code activate()}，否则候选版本标记 REJECTED 并保留现有 active。
 * <p>检查项：候选评测 Recall@5 / nDCG@5 / 拒答率 / 拒答准确率、冷 P95 延迟、与 active 的 Recall 回退幅度。
 * 其余隔离类门禁（失效法律隔离、ACL 隔离、投毒文档隔离、evidenceId 重复、索引完整性）
 * 由分块 fail-closed、安全扫描与 {@code candidateCount==segmentCount} 在构建路径内保证。</p>
 */
@Component
public class LegalIndexPublicationGate {

    private static final Logger log = LoggerFactory.getLogger(LegalIndexPublicationGate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagEvaluator evaluator;
    private final LegalIndexVersionProvider versions;
    private final com.bank.aml.observability.MetricsRecorder metrics;
    private final double minRecallAt5;
    private final double minNdcgAt5;
    private final double maxRecallDropVsActivePp;
    private final double minAbstentionAccuracy;
    private final double minNoAnswerRefusalRate;
    private final double maxColdP95Ms;

    public LegalIndexPublicationGate(RagEvaluator evaluator, LegalIndexVersionProvider versions,
                                     @Value("${aml.rag.publication-gate.min-recall-at5:90.0}") double minRecallAt5,
                                     @Value("${aml.rag.publication-gate.min-ndcg-at5:80.0}") double minNdcgAt5,
                                     @Value("${aml.rag.publication-gate.max-recall-drop-vs-active-pp:2.0}") double maxRecallDropVsActivePp,
                                     @Value("${aml.rag.publication-gate.min-abstention-accuracy:95.0}") double minAbstentionAccuracy,
                                     @Value("${aml.rag.publication-gate.min-no-answer-refusal-rate:95.0}") double minNoAnswerRefusalRate,
                                     @Value("${aml.rag.publication-gate.max-cold-p95-ms:750.0}") double maxColdP95Ms) {
        this(evaluator, versions, null, minRecallAt5, minNdcgAt5, maxRecallDropVsActivePp,
                minAbstentionAccuracy, minNoAnswerRefusalRate, maxColdP95Ms);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LegalIndexPublicationGate(RagEvaluator evaluator, LegalIndexVersionProvider versions,
                                     com.bank.aml.observability.MetricsRecorder metrics,
                                     @Value("${aml.rag.publication-gate.min-recall-at5:90.0}") double minRecallAt5,
                                     @Value("${aml.rag.publication-gate.min-ndcg-at5:80.0}") double minNdcgAt5,
                                     @Value("${aml.rag.publication-gate.max-recall-drop-vs-active-pp:2.0}") double maxRecallDropVsActivePp,
                                     @Value("${aml.rag.publication-gate.min-abstention-accuracy:95.0}") double minAbstentionAccuracy,
                                     @Value("${aml.rag.publication-gate.min-no-answer-refusal-rate:95.0}") double minNoAnswerRefusalRate,
                                     @Value("${aml.rag.publication-gate.max-cold-p95-ms:750.0}") double maxColdP95Ms) {
        this.evaluator = evaluator;
        this.versions = versions;
        this.metrics = metrics;
        this.minRecallAt5 = minRecallAt5;
        this.minNdcgAt5 = minNdcgAt5;
        this.maxRecallDropVsActivePp = maxRecallDropVsActivePp;
        this.minAbstentionAccuracy = minAbstentionAccuracy;
        this.minNoAnswerRefusalRate = minNoAnswerRefusalRate;
        this.maxColdP95Ms = maxColdP95Ms;
    }

    public record GateResult(boolean passed, String qualityJson, List<String> failures) {
        public static final GateResult PASSED_WITHOUT_REPORT =
                new GateResult(true, "{\"smokeSearch\":true,\"gates\":[]}", List.of());
    }

    /** 对候选版本执行门禁评测；达标返回通过并携带质量报告。 */
    public GateResult evaluate(String candidateVersion, int segmentCount) {
        List<String> failures = new ArrayList<>();
        RagEvaluator.RagEvalReport report = evaluator.evaluateCandidate(candidateVersion);
        if (report.recallAt5() < minRecallAt5) {
            failures.add("recallAt5=" + report.recallAt5() + " < " + minRecallAt5);
        }
        if (report.ndcgAt5() < minNdcgAt5) {
            failures.add("ndcgAt5=" + report.ndcgAt5() + " < " + minNdcgAt5);
        }
        if (report.noAnswerRefusalRate() < minNoAnswerRefusalRate) {
            failures.add("noAnswerRefusalRate=" + report.noAnswerRefusalRate() + " < " + minNoAnswerRefusalRate);
        }
        if (report.abstentionAccuracy() < minAbstentionAccuracy) {
            failures.add("abstentionAccuracy=" + report.abstentionAccuracy() + " < " + minAbstentionAccuracy);
        }
        if (report.coldP95Ms() > maxColdP95Ms) {
            failures.add("coldP95Ms=" + report.coldP95Ms() + " > " + maxColdP95Ms);
        }
        String active = versions.activeVersion();
        if (!active.isBlank() && !active.equals(candidateVersion)) {
            RagEvaluator.RagEvalReport activeReport = evaluator.evaluate();
            double drop = activeReport.recallAt5() - report.recallAt5();
            if (drop > maxRecallDropVsActivePp) {
                failures.add("recallDropVsActivePp=" + drop + " > " + maxRecallDropVsActivePp
                        + " (active=" + activeReport.recallAt5() + ", candidate=" + report.recallAt5() + ")");
            }
            double overlap = candidateActiveOverlap(activeReport, report);
            if (metrics != null) metrics.ragCandidateActiveOverlap(overlap);
        }
        ObjectNode gates = MAPPER.createObjectNode();
        for (String failure : failures) gates.put("failed_" + (gates.size() + 1), failure);
        ObjectNode quality = MAPPER.createObjectNode();
        quality.put("segmentCount", segmentCount);
        quality.put("passed", failures.isEmpty());
        quality.put("recallAt5", report.recallAt5());
        quality.put("ndcgAt5", report.ndcgAt5());
        quality.put("noAnswerRefusalRate", report.noAnswerRefusalRate());
        quality.put("abstentionAccuracy", report.abstentionAccuracy());
        quality.put("coldP95Ms", report.coldP95Ms());
        quality.put("coldP50Ms", report.coldP50Ms());
        quality.put("warmP95Ms", report.warmP95Ms());
        quality.set("segmentedMs", MAPPER.valueToTree(report.segmentedMs().averageMs()));
        quality.set("failedGates", gates);
        if (!failures.isEmpty()) {
            report.details().stream()
                    // 可回答样例即便召回了正确文档，只要被错误拒答，同样是门禁失败；
                    // 过去这里只记录 rank<1，导致“命中但被阈值拒答”的根因无法观察。
                    .filter(detail -> (detail.answerable() && (detail.rank() < 1 || detail.abstained()))
                            || (!detail.answerable() && !detail.abstained()))
                    .forEach(detail -> log.warn(
                            "RAG 发布门禁失败明细 id={} category={} answerable={} rank={} status={} support={} ids={} scores={}",
                            detail.id(), detail.category(), detail.answerable(), detail.rank(),
                            detail.retrievalStatus(), detail.support(), detail.returnedEvidenceIds(),
                            detail.relevanceScores()));
        }
        return new GateResult(failures.isEmpty(), quality.toString(), List.copyOf(failures));
    }

    /** 候选命中与 active 命中的重合比例（观测：新版本改动是否造成大面积替代）。 */
    private double candidateActiveOverlap(RagEvaluator.RagEvalReport active, RagEvaluator.RagEvalReport candidate) {
        int count = 0;
        int intersect = 0;
        int n = Math.min(active.details().size(), candidate.details().size());
        if (n == 0) return 0;
        for (int i = 0; i < n; i++) {
            java.util.Set<String> a = new java.util.HashSet<>(active.details().get(i).returnedEvidenceIds());
            java.util.Set<String> c = new java.util.HashSet<>(candidate.details().get(i).returnedEvidenceIds());
            if (c.isEmpty()) continue;
            a.retainAll(c);
            intersect += a.size();
            count += c.size();
        }
        return count == 0 ? 0 : (double) intersect / count;
    }
}
