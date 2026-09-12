package com.bank.aml.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.LocalDateTime;

/** 补充尽调证据的可信元数据索引；附件正文仍由来源系统保管。 */
@Entity
@Table(name = "enhanced_due_diligence_evidence")
public class EnhancedDueDiligenceEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long requestId;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false, length = 64)
    private String requiredItemCode;

    @Column(nullable = false, length = 64)
    private String sourceSystem;

    @Column(nullable = false, length = 128)
    private String sourceReference;

    // SHA-256 是固定 64 位十六进制值；显式 CHAR 保持 JPA 校验与 Flyway V21 一致。
    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String contentSha256;

    @Column(nullable = false, length = 64)
    private String capturedBy;

    @Column(nullable = false)
    private LocalDateTime capturedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null)
            createdAt = LocalDateTime.now(Clock.systemUTC());
        if (capturedAt == null)
            capturedAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getRequestId() {
        return requestId;
    }

    public void setRequestId(Long requestId) {
        this.requestId = requestId;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public String getRequiredItemCode() {
        return requiredItemCode;
    }

    public void setRequiredItemCode(String requiredItemCode) {
        this.requiredItemCode = requiredItemCode;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public String getCapturedBy() {
        return capturedBy;
    }

    public void setCapturedBy(String capturedBy) {
        this.capturedBy = capturedBy;
    }

    public LocalDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(LocalDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

}
