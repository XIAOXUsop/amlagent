package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 核验依据版本：按案件追加冻结范围与事实摘要（v2 计划 §12）。 */
@Entity
@Table(name = "verification_basis")
public class VerificationBasis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private int basisRevision;

    /** 冻结的交易/客户事实范围（JSON，含密文引用，不落敏感正文）。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String scopeJson;

    @Column(nullable = false)
    private LocalDateTime sourceCutoff;

    @Column(nullable = false, length = 64)
    private String scopeDigest;

    @Column(nullable = false, length = 64)
    private String basisDigest;

    @Column(nullable = false, length = 64)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public int getBasisRevision() { return basisRevision; }
    public void setBasisRevision(int value) { basisRevision = value; }
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String value) { scopeJson = value; }
    public LocalDateTime getSourceCutoff() { return sourceCutoff; }
    public void setSourceCutoff(LocalDateTime value) { sourceCutoff = value; }
    public String getScopeDigest() { return scopeDigest; }
    public void setScopeDigest(String value) { scopeDigest = value; }
    public String getBasisDigest() { return basisDigest; }
    public void setBasisDigest(String value) { basisDigest = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
