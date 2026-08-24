package com.bank.aml.rag;

import com.bank.aml.observability.MetricsRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 在召回器外建立授权、适用期、相关性、支持概率与判定原因门控。
 * <ul>
 *   <li>召回：基于 {@code SearchHit}（保留 dense/lexical/fusion/rerank 各阶段分数与命中原因）；</li>
 *   <li>支持判定：用校准后的 supportProbability 按问题类型阈值给出明确的 判定原因；</li>
 *   <li>过期/越权/冲突分别输出 EVIDENCE_EXPIRED / EVIDENCE_ACCESS_DENIED / EVIDENCE_CONFLICT。</li>
 * </ul>
 */
@Component
public class EnterpriseLegalRetriever {
    private static final double MIN_MEANINGFUL_SUPPORT = 0.20;

    private final LegalDocumentSearcher searcher;
    private final LegalIndexVersionProvider versions;
    private final MetricsRecorder metrics;
    private final int recallMultiplier;
    private final RagContextSelector contextSelector;
    private final int maxPerDocument;
    private final int maxContextCharacters;
    private final SupportProbabilityCalibrator calibrator;
    private final LegalQueryAnalyzer queryAnalyzer;
    private final SupportPolicy supportPolicy;

    public EnterpriseLegalRetriever(LegalDocumentSearcher searcher, LegalIndexVersionProvider versions,
                                    MetricsRecorder metrics,
                                    @Value("${aml.rag.retrieval.recall-multiplier:4}") int recallMultiplier) {
        this(searcher, versions, metrics, new RagContextSelector(), Math.max(2, recallMultiplier), 3, 8_000,
                new SupportProbabilityCalibrator(), new LegalQueryAnalyzer(), new SupportPolicy());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EnterpriseLegalRetriever(LegalDocumentSearcher searcher, LegalIndexVersionProvider versions,
                                    MetricsRecorder metrics, RagContextSelector contextSelector,
                                    @Value("${aml.rag.retrieval.recall-multiplier:4}") int recallMultiplier,
                                    @Value("${aml.rag.retrieval.max-per-document:3}") int maxPerDocument,
                                    @Value("${aml.rag.retrieval.max-context-characters:8000}") int maxContextCharacters,
                                    SupportProbabilityCalibrator calibrator,
                                    LegalQueryAnalyzer queryAnalyzer, SupportPolicy supportPolicy) {
        this.searcher = searcher;
        this.versions = versions;
        this.metrics = metrics;
        this.contextSelector = contextSelector;
        this.recallMultiplier = Math.max(2, recallMultiplier);
        this.maxPerDocument = Math.max(1, maxPerDocument);
        this.maxContextCharacters = Math.max(1000, maxContextCharacters);
        this.calibrator = calibrator == null ? new SupportProbabilityCalibrator() : calibrator;
        this.queryAnalyzer = queryAnalyzer == null ? new LegalQueryAnalyzer() : queryAnalyzer;
        this.supportPolicy = supportPolicy == null ? new SupportPolicy() : supportPolicy;
    }

    public RetrievalResponse retrieve(RetrievalRequest request) {
        long start = System.nanoTime();
        String version = versions.versionFor(request);
        if (version.isBlank()) {
            return finish(RetrievalResponse.Status.INDEX_UNAVAILABLE, EvidenceSupport.NO_RELEVANT_EVIDENCE,
                    List.of(), List.of(), "", start);
        }
        // 未知异常必须向上抛出，交给工作流重试/死信；禁止把程序缺陷伪装成可接受的空证据。
        final List<SearchHit> recalled = new ArrayList<>(searcher.searchScored(
                request, Math.min(20, request.topK() * recallMultiplier)));
        long afterRecall = System.nanoTime();
        if (recalled.isEmpty()) {
            RetrievalTimings.add("filter", elapsedMs(afterRecall));
            return finish(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE, EvidenceSupport.NO_RELEVANT_EVIDENCE,
                    List.of(), recalled, version, start);
        }

        // 一味适用性过滤：TRUSTED + 辖区 + 生效窗口
        List<SearchHit> applicable = recalled.stream().filter(hit -> applicable(hit.document(), request)).toList();
        if (applicable.isEmpty()) {
            RetrievalTimings.add("filter", elapsedMs(afterRecall));
            boolean anyExpired = recalled.stream().anyMatch(hit -> expired(hit.document(), request));
            if (anyExpired) metrics.ragExpiredFiltered();
            boolean anyUntrusted = recalled.stream()
                    .anyMatch(hit -> !"TRUSTED".equals(hit.document().metadata().securityStatus()));
            EvidenceSupport cause = anyExpired ? EvidenceSupport.EVIDENCE_EXPIRED
                    : anyUntrusted ? EvidenceSupport.NO_RELEVANT_EVIDENCE
                    : recalled.stream().anyMatch(hit -> hasScope(hit.document(), request))
                    ? EvidenceSupport.NO_RELEVANT_EVIDENCE : EvidenceSupport.EVIDENCE_ACCESS_DENIED;
            return finish(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE, cause, List.of(), recalled, version, start);
        }
        List<SearchHit> authorized = applicable.stream().filter(hit -> hasScope(hit.document(), request)).toList();
        int aclFiltered = applicable.size() - authorized.size();
        if (aclFiltered > 0) metrics.ragAclFiltered();
        if (authorized.isEmpty()) {
            RetrievalTimings.add("filter", elapsedMs(afterRecall));
            return finish(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE, EvidenceSupport.EVIDENCE_ACCESS_DENIED,
                    List.of(), recalled, version, start);
        }
        // 先执行可信/时效/ACL 门控（对抗夹具可验证具体拒绝原因），再拒绝 AML 法规域外问题。
        if (!queryAnalyzer.isAmlLegalDomain(request.query())) {
            RetrievalTimings.add("filter", elapsedMs(afterRecall));
            return finish(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE,
                    EvidenceSupport.NO_RELEVANT_EVIDENCE, List.of(), recalled, version, start);
        }
        List<SearchHit> withSupport = authorized.stream()
                .map(hit -> hit.support(probabilityOf(hit)))
                .toList();

        double absoluteFloor = Math.max(MIN_MEANINGFUL_SUPPORT, request.minRelevance());
        withSupport = withSupport.stream()
                .filter(hit -> hit.supportProbability() != null && hit.supportProbability() >= absoluteFloor)
                .toList();
        if (withSupport.isEmpty()) {
            RetrievalTimings.add("filter", elapsedMs(afterRecall));
            return finish(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE, EvidenceSupport.NO_RELEVANT_EVIDENCE,
                    List.of(), recalled, version, start);
        }
        if (conflict(request, withSupport)) {
            RetrievalTimings.add("filter", elapsedMs(afterRecall));
            return finish(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE, EvidenceSupport.EVIDENCE_CONFLICT,
                    List.of(), recalled, version, start);
        }

        // 上下文预算：单文档限额与去重
        List<LegalDoc> documents = withSupport.stream().map(SearchHit::document).toList();
        List<LegalDoc> selected = contextSelector.select(documents, maxPerDocument, maxContextCharacters, 0.88)
                .stream().limit(request.topK()).toList();
        var selectedIds = selected.stream().map(LegalDoc::evidenceId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<SearchHit> finalHits = withSupport.stream().filter(hit -> selectedIds.contains(hit.document().evidenceId()))
                .toList();

        double threshold = supportPolicy.thresholdFor(queryAnalyzer.parse(request.query()).intent());
        double strongest = finalHits.stream().mapToDouble(hit -> hit.supportProbability() == null ? 0 : hit.supportProbability())
                .max().orElse(0);

        List<RetrievalResponse.RetrievalHit> hits = new ArrayList<>();
        int rank = 1;
        for (SearchHit hit : finalHits) {
            hits.add(new RetrievalResponse.RetrievalHit(hit.document(), rank++,
                    hit.supportProbability() == null ? 0 : hit.supportProbability()));
        }
        RetrievalResponse.Status status;
        EvidenceSupport support;
        if (strongest >= threshold) {
            status = RetrievalResponse.Status.SUPPORTED;
            support = EvidenceSupport.SUPPORTED;
        } else {
            status = RetrievalResponse.Status.INSUFFICIENT_EVIDENCE;
            support = EvidenceSupport.WEAK_SUPPORT;
        }
        RetrievalTimings.add("filter", elapsedMs(afterRecall));
        return finish(status, support, hits, recalled, version, start);
    }

    /**
     * 降级时使用各召回通道的绝对分数，不再用“本批第一名固定为 0.9”的相对 RRF 归一化。
     * 词法分映射只表示字段命中强度；两路同时命中时按独立证据合并概率。
     */
    private double probabilityOf(SearchHit hit) {
        Double raw = hit.rerankScore();
        if (raw != null) return calibrator.calibrate(raw);
        double dense = hit.denseScore() == null ? 0 : clamp(hit.denseScore());
        double lexical = hit.lexicalScore() == null ? 0
                : 1.0 - Math.exp(-Math.max(0, hit.lexicalScore()) / 2.5);
        if (dense > 0 && lexical > 0) return clamp(1.0 - (1.0 - dense) * (1.0 - lexical));
        return Math.max(dense, lexical);
    }

    private double clamp(double value) { return Math.max(0, Math.min(1, value)); }

    private boolean applicable(LegalDoc doc, RetrievalRequest request) {
        LegalEvidenceMetadata md = doc.metadata();
        if (!"TRUSTED".equals(md.securityStatus())) return false;
        if (!request.jurisdiction().equalsIgnoreCase(md.jurisdiction())) return false;
        return inEffectiveWindow(doc.metadata(), request.asOfTime());
    }

    private boolean inEffectiveWindow(LegalEvidenceMetadata md, java.time.Instant asOfTime) {
        LocalDate asOf = asOfTime.atZone(ZoneOffset.UTC).toLocalDate();
        return (md.effectiveFrom() == null || !asOf.isBefore(md.effectiveFrom()))
                && (md.effectiveTo() == null || !asOf.isAfter(md.effectiveTo()));
    }

    private boolean expired(LegalDoc doc, RetrievalRequest request) {
        if (!"TRUSTED".equals(doc.metadata().securityStatus())) return false;
        if (!request.jurisdiction().equalsIgnoreCase(doc.metadata().jurisdiction())) return false;
        return !inEffectiveWindow(doc.metadata(), request.asOfTime());
    }

    private boolean hasScope(LegalDoc doc, RetrievalRequest request) {
        return doc.metadata().accessScopes().stream().anyMatch(request.accessScopes()::contains);
    }

    /**
     * 仅对冻结/名单处置这一具体命题检测“立即冻结”与“可以等待审批再冻结”的直接冲突。
     * 禁止把其他条文中无关的“可以”（如可以现场检查）误判为相互矛盾的法律结论。
     */
    private boolean conflict(RetrievalRequest request, List<SearchHit> hits) {
        if (queryAnalyzer.parse(request.query()).intent() != LegalQueryAnalyzer.QueryIntent.HIGH_RISK_DISPOSAL) {
            return false;
        }
        if (!containsAny(request.query(), "冻结", "名单", "恐怖活动", "制裁")) return false;
        java.util.regex.Pattern immediate = java.util.regex.Pattern.compile(
                "(?:立即|不得拖延).{0,24}冻结|冻结.{0,24}(?:立即|不得拖延)");
        java.util.regex.Pattern deferred = java.util.regex.Pattern.compile(
                "(?:可以等待|等待|审批后|批准后).{0,24}冻结|冻结.{0,24}(?:可以等待|等待审批|审批后|批准后)");
        boolean requiresImmediate = hits.stream().limit(5)
                .anyMatch(hit -> immediate.matcher(hit.document().content()).find());
        boolean allowsDeferral = hits.stream().limit(5)
                .anyMatch(hit -> deferred.matcher(hit.document().content()).find());
        return requiresImmediate && allowsDeferral;
    }

    private boolean containsAny(String value, String... tokens) {
        if (value == null) return false;
        for (String token : tokens) if (value.contains(token)) return true;
        return false;
    }

    private long elapsedMs(long start) {
        return Math.max(0, (System.nanoTime() - start) / 1_000_000);
    }

    private RetrievalResponse finish(RetrievalResponse.Status status, EvidenceSupport support,
                                     List<RetrievalResponse.RetrievalHit> hits,
                                     List<SearchHit> traces, String indexVersion, long start) {
        metrics.ragRetrieval(status.name(), Math.max(0, (System.nanoTime() - start) / 1_000_000), hits.size());
        if (status != RetrievalResponse.Status.SUPPORTED) {
            metrics.ragAbstention(status.name());
            if (hits.isEmpty()) metrics.ragZeroHit();
        }
        return new RetrievalResponse(status, indexVersion, hits, support, traces);
    }
}
