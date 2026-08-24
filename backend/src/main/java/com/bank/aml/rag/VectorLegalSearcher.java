package com.bank.aml.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 PGVector 的向量法规语义召回（DENSE 通道）。
 */
@Component
public class VectorLegalSearcher implements LegalDocumentSearcher {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final LegalIndexVersionProvider indexVersions;
    private final QueryEmbeddingCache embeddingCache;
    private final com.bank.aml.observability.MetricsRecorder metrics;

    public VectorLegalSearcher(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                               LegalIndexVersionProvider indexVersions) {
        this(embeddingModel, embeddingStore, indexVersions, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VectorLegalSearcher(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                               LegalIndexVersionProvider indexVersions, QueryEmbeddingCache embeddingCache,
                               com.bank.aml.observability.MetricsRecorder metrics) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.indexVersions = indexVersions;
        this.embeddingCache = embeddingCache;
        this.metrics = metrics;
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        return searchScoredInternal(query, topK, null).stream().map(SearchHit::document).toList();
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        return searchScored(request, topK).stream().map(SearchHit::document).toList();
    }

    @Override
    public List<SearchHit> searchScored(RetrievalRequest request, int topK) {
        return searchScoredInternal(request.query(), topK, request);
    }

    private List<SearchHit> searchScoredInternal(String query, int topK, RetrievalRequest request) {
        long started = System.nanoTime();
        String version = indexVersions.versionFor(request);
        if (version.isBlank()) return List.of();
        Embedding queryEmbedding = embeddingCache == null
                ? embeddingModel.embed(query).content()
                : embeddingCache.getOrEmbed(query, version, embeddingModel, metrics);
        dev.langchain4j.store.embedding.filter.Filter filter =
                dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey("corpusVersion").isEqualTo(version);
        if (request != null) {
            filter = filter.and(dev.langchain4j.store.embedding.filter.MetadataFilterBuilder
                    .metadataKey("jurisdiction").isEqualTo(request.jurisdiction()));
            dev.langchain4j.store.embedding.filter.Filter scopeFilter = null;
            for (String scope : request.accessScopes()) {
                var one = dev.langchain4j.store.embedding.filter.MetadataFilterBuilder
                        .metadataKey("accessScopes").containsString(scope);
                scopeFilter = scopeFilter == null ? one : scopeFilter.or(one);
            }
            if (scopeFilter != null) filter = filter.and(scopeFilter);
        }
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(topK)
                        .filter(filter)
                        .build());
        List<SearchHit> docs = new ArrayList<>();
        int rank = 1;
        for (var match : result.matches()) {
            TextSegment seg = match.embedded();
            Metadata md = seg.metadata();
            LegalDoc doc = new LegalDoc(
                    value(md, "evidenceId"),
                    value(md, "title"),
                    value(md, "documentNumber"),
                    value(md, "articleNumber"),
                    seg.text(), metadata(md));
            docs.add(SearchHit.dense(rank++, match.score(), doc));
        }
        RetrievalTimings.add("dense", elapsedMs(started));
        return docs;
    }

    private LegalEvidenceMetadata metadata(Metadata md) {
        try {
            String documentId = value(md, "documentId");
            String jurisdiction = value(md, "jurisdiction");
            String accessScopes = value(md, "accessScopes");
            String contentDigest = value(md, "contentDigest");
            String corpusVersion = value(md, "corpusVersion");
            String securityStatus = value(md, "securityStatus");
            if (documentId.isBlank() || jurisdiction.isBlank() || accessScopes.isBlank()
                    || contentDigest.isBlank() || corpusVersion.isBlank() || !"TRUSTED".equals(securityStatus)) {
                return LegalEvidenceMetadata.untrustedMetadata();
            }
            return new LegalEvidenceMetadata(documentId, value(md, "parentSection"), jurisdiction,
                    date(value(md, "effectiveFrom")), date(value(md, "effectiveTo")), scopes(accessScopes),
                    contentDigest, corpusVersion, value(md, "sourceFile"), securityStatus);
        } catch (RuntimeException malformedMetadata) {
            return LegalEvidenceMetadata.untrustedMetadata();
        }
    }

    private java.time.LocalDate date(String value) {
        return value == null || value.isBlank() ? null : java.time.LocalDate.parse(value);
    }

    private String value(Metadata metadata, String key) {
        try { return metadata.getString(key); } catch (Exception ignored) { return ""; }
    }

    private java.util.Set<String> scopes(String value) {
        if (value == null || value.isBlank()) return java.util.Set.of("QUARANTINED");
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }
}