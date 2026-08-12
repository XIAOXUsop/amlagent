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

    public VectorLegalSearcher(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(topK)
                        .build());
        return result.matches().stream()
                .map(m -> {
                    TextSegment seg = m.embedded();
                    Metadata md = seg.metadata();
                    return new LegalDoc(
                            md.getString("evidenceId"),
                            md.getString("title"),
                            md.getString("documentNumber"),
                            md.getString("articleNumber"),
                            seg.text());
                })
                .toList();
    }
}
