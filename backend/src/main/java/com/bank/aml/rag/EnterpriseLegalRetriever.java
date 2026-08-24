package com.bank.aml.rag;

import com.bank.aml.observability.MetricsRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 在召回器外建立授权、适用期、相关性和拒答门控。 */
@Component
public class EnterpriseLegalRetriever {
    private static final List<String> STOP_PHRASES = List.of(
            "根据", "关于", "什么", "是否", "需要", "应当", "如何", "哪些", "多少", "能否",
            "客户", "银行", "金融机构", "规定", "要求", "相关");

    private final LegalDocumentSearcher searcher;
    private final LegalIndexVersionProvider versions;
    private final MetricsRecorder metrics;
    private final int recallMultiplier;
    private final RagContextSelector contextSelector;
    private final int maxPerDocument;
    private final int maxContextCharacters;

    public EnterpriseLegalRetriever(LegalDocumentSearcher searcher, LegalIndexVersionProvider versions,
                                    MetricsRecorder metrics,
                                    @Value("${aml.rag.retrieval.recall-multiplier:4}") int recallMultiplier) {
        this.searcher = searcher;
        this.versions = versions;
        this.metrics = metrics;
        this.recallMultiplier = Math.max(2, recallMultiplier);
        this.contextSelector = new RagContextSelector();
        this.maxPerDocument = 3;
        this.maxContextCharacters = 8_000;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EnterpriseLegalRetriever(LegalDocumentSearcher searcher, LegalIndexVersionProvider versions,
                                    MetricsRecorder metrics, RagContextSelector contextSelector,
                                    @Value("${aml.rag.retrieval.recall-multiplier:4}") int recallMultiplier,
                                    @Value("${aml.rag.retrieval.max-per-document:3}") int maxPerDocument,
                                    @Value("${aml.rag.retrieval.max-context-characters:8000}") int maxContextCharacters) {
        this.searcher = searcher;
        this.versions = versions;
        this.metrics = metrics;
        this.contextSelector = contextSelector;
        this.recallMultiplier = Math.max(2, recallMultiplier);
        this.maxPerDocument = Math.max(1, maxPerDocument);
        this.maxContextCharacters = Math.max(1000, maxContextCharacters);
    }

    public RetrievalResponse retrieve(RetrievalRequest request) {
        long start = System.nanoTime();
        String version = versions.activeVersion();
        if (version.isBlank()) return finish(RetrievalResponse.Status.INDEX_UNAVAILABLE, version, List.of(), start);
        // 未知异常必须向上抛出，交给工作流重试/死信；禁止把程序缺陷伪装成可接受的空证据。
        final List<LegalDoc> recalled = searcher.search(
                request, Math.min(20, request.topK() * recallMultiplier));
        if (recalled.isEmpty()) return finish(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE, version, List.of(), start);

        List<LegalDoc> applicable = recalled.stream().filter(doc -> applicable(doc, request)).toList();
        if (applicable.isEmpty()) {
            return finish(RetrievalResponse.Status.NO_RELEVANT_EVIDENCE, version, List.of(), start);
        }
        List<LegalDoc> authorized = applicable.stream().filter(doc -> hasScope(doc, request)).toList();
        if (authorized.isEmpty()) return finish(RetrievalResponse.Status.ACCESS_DENIED, version, List.of(), start);
        java.util.Map<String, Double> relevance = new java.util.LinkedHashMap<>();
        List<LegalDoc> relevant = authorized.stream().filter(doc -> {
            double score = lexicalCoverage(request.query() + " " + request.topic(),
                    doc.title() + " " + doc.articleNumber() + " " + doc.content());
            relevance.put(doc.evidenceId(), score);
            return score >= request.minRelevance();
        }).toList();
        relevant = contextSelector.select(relevant, maxPerDocument, maxContextCharacters, 0.88);

        List<RetrievalResponse.RetrievalHit> hits = new ArrayList<>();
        int rank = 1;
        for (LegalDoc doc : relevant) {
            hits.add(new RetrievalResponse.RetrievalHit(doc, rank++, relevance.get(doc.evidenceId())));
            if (hits.size() == request.topK()) break;
        }
        double strongThreshold = Math.max(0.20, request.minRelevance() * 2);
        double strongestScore = hits.stream().mapToDouble(RetrievalResponse.RetrievalHit::relevanceScore)
                .max().orElse(0);
        RetrievalResponse.Status status = hits.isEmpty() ? RetrievalResponse.Status.NO_RELEVANT_EVIDENCE
                : strongestScore >= strongThreshold
                ? RetrievalResponse.Status.SUPPORTED : RetrievalResponse.Status.INSUFFICIENT_EVIDENCE;
        return finish(status, version, hits, start);
    }

    private boolean applicable(LegalDoc doc, RetrievalRequest request) {
        LegalEvidenceMetadata md = doc.metadata();
        if (!"TRUSTED".equals(md.securityStatus())) return false;
        if (!request.jurisdiction().equalsIgnoreCase(md.jurisdiction())) return false;
        LocalDate asOf = request.asOfTime().atZone(ZoneOffset.UTC).toLocalDate();
        return (md.effectiveFrom() == null || !asOf.isBefore(md.effectiveFrom()))
                && (md.effectiveTo() == null || !asOf.isAfter(md.effectiveTo()));
    }

    private boolean hasScope(LegalDoc doc, RetrievalRequest request) {
        return doc.metadata().accessScopes().stream().anyMatch(request.accessScopes()::contains);
    }

    /** 中文无需外部分词器的保守字符二元组覆盖率，用于拒绝完全无关的 ANN 最近邻。 */
    static double lexicalCoverage(String query, String document) {
        String q = normalize(query);
        String d = normalize(document);
        for (String phrase : STOP_PHRASES) q = q.replace(phrase, "");
        Set<String> grams = bigrams(q);
        if (grams.isEmpty()) return d.contains(q) && !q.isEmpty() ? 1.0 : 0.0;
        long matched = grams.stream().filter(d::contains).count();
        return (double) matched / grams.size();
    }

    private static Set<String> bigrams(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i + 1 < value.length(); i++) result.add(value.substring(i, i + 2));
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{IsHan}A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    private RetrievalResponse finish(RetrievalResponse.Status status, String version,
                                     List<RetrievalResponse.RetrievalHit> hits, long start) {
        metrics.ragRetrieval(status.name(), Math.max(0, (System.nanoTime() - start) / 1_000_000), hits.size());
        return new RetrievalResponse(status, version, hits);
    }
}
