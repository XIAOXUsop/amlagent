package com.bank.aml.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 PGVector 的向量法规语义召回。
 */
@Component
public class VectorLegalSearcher implements LegalDocumentSearcher {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final LegalIndexVersionProvider indexVersions;

    public VectorLegalSearcher(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore,
                               LegalIndexVersionProvider indexVersions) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.indexVersions = indexVersions;
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        return searchInternal(query, topK, null);
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        return searchInternal(request.query(), topK, request);
    }

    private List<LegalDoc> searchInternal(String query, int topK, RetrievalRequest request) {
        String version = indexVersions.activeVersion();
        if (version.isBlank()) return List.of();
        Embedding queryEmbedding = embeddingModel.embed(query).content();
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
        return result.matches().stream()
                .map(m -> {
                    TextSegment seg = m.embedded();
                    Metadata md = seg.metadata();
                    return new LegalDoc(
                            value(md, "evidenceId"),
                            value(md, "title"),
                            value(md, "documentNumber"),
                            value(md, "articleNumber"),
                            seg.text(), metadata(md));
                })
                .toList();
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
}
