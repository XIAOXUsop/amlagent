package com.bank.aml.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "rag_index_manifest")
public class RagIndexManifestEntity {
    @Id @Column(length = 64, columnDefinition = "char(64)") private String indexVersion;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String corpusHash;
    @Column(nullable = false, length = 64) private String chunkerVersion;
    @Column(nullable = false, length = 64) private String metadataSchemaVersion;
    @Column(nullable = false, length = 64) private String embeddingProvider;
    @Column(nullable = false, length = 128) private String embeddingModel;
    @Column(nullable = false, length = 128) private String embeddingRevision;
    @Column(nullable = false, length = 128) private String embeddingModelHash;
    @Column(nullable = false) private int embeddingDimensions;
    @Column(nullable = false, length = 32) private String distanceMetric;
    @Column(nullable = false, length = 32) private String status;
    @Column(nullable = false) private int segmentCount;
    @Column(columnDefinition = "TEXT") private String qualityReportJson;
    @Column(length = 128) private String failureCode;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime activatedAt;
    private LocalDateTime retiredAt;
    @Column(nullable = false) private LocalDateTime updatedAt;

    public String getIndexVersion() { return indexVersion; }
    public void setIndexVersion(String v) { this.indexVersion = v; }
    public String getCorpusHash() { return corpusHash; }
    public void setCorpusHash(String v) { this.corpusHash = v; }
    public String getChunkerVersion() { return chunkerVersion; }
    public void setChunkerVersion(String v) { this.chunkerVersion = v; }
    public String getMetadataSchemaVersion() { return metadataSchemaVersion; }
    public void setMetadataSchemaVersion(String v) { this.metadataSchemaVersion = v; }
    public String getEmbeddingProvider() { return embeddingProvider; }
    public void setEmbeddingProvider(String v) { this.embeddingProvider = v; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String v) { this.embeddingModel = v; }
    public String getEmbeddingRevision() { return embeddingRevision; }
    public void setEmbeddingRevision(String v) { this.embeddingRevision = v; }
    public String getEmbeddingModelHash() { return embeddingModelHash; }
    public void setEmbeddingModelHash(String v) { this.embeddingModelHash = v; }
    public int getEmbeddingDimensions() { return embeddingDimensions; }
    public void setEmbeddingDimensions(int v) { this.embeddingDimensions = v; }
    public String getDistanceMetric() { return distanceMetric; }
    public void setDistanceMetric(String v) { this.distanceMetric = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public int getSegmentCount() { return segmentCount; }
    public void setSegmentCount(int v) { this.segmentCount = v; }
    public String getQualityReportJson() { return qualityReportJson; }
    public void setQualityReportJson(String v) { this.qualityReportJson = v; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String v) { this.failureCode = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(LocalDateTime v) { this.activatedAt = v; }
    public LocalDateTime getRetiredAt() { return retiredAt; }
    public void setRetiredAt(LocalDateTime v) { this.retiredAt = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { this.updatedAt = v; }
}
