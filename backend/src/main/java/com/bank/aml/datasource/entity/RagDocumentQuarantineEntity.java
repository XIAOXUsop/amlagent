package com.bank.aml.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "rag_document_quarantine")
public class RagDocumentQuarantineEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 255) private String sourceFile;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String fileHash;
    @Column(nullable = false, length = 512) private String reasonCodes;
    @Column(nullable = false) private LocalDateTime detectedAt;
    public Long getId() { return id; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String v) { sourceFile = v; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String v) { fileHash = v; }
    public String getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(String v) { reasonCodes = v; }
    public LocalDateTime getDetectedAt() { return detectedAt; }
    public void setDetectedAt(LocalDateTime v) { detectedAt = v; }
}
