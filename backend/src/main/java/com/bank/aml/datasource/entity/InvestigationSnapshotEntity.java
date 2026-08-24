package com.bank.aml.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDateTime;

/** 不可变尽调快照的加密审计归档。 */
@Entity
@Table(name = "investigation_snapshot")
public class InvestigationSnapshotEntity {
    @Id @Column(length = 64) private String snapshotId;
    @Column(nullable = false) private Long caseId;
    @Column(nullable = false) private int executionVersion;
    @Column(nullable = false) private Instant asOfTime;
    @Column(nullable = false, length = 64) private String sourceSystem;
    @Column(nullable = false, length = 128) private String sourceVersion;
    @Column(nullable = false, length = 128) private String legalIndexVersion;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String sourceDigest;
    @Lob @Column(nullable = false, columnDefinition = "MEDIUMTEXT") private String payloadCiphertext;
    @Column(nullable = false) private LocalDateTime createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public int getExecutionVersion() { return executionVersion; }
    public void setExecutionVersion(int executionVersion) { this.executionVersion = executionVersion; }
    public Instant getAsOfTime() { return asOfTime; }
    public void setAsOfTime(Instant asOfTime) { this.asOfTime = asOfTime; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String sourceVersion) { this.sourceVersion = sourceVersion; }
    public String getLegalIndexVersion() { return legalIndexVersion; }
    public void setLegalIndexVersion(String legalIndexVersion) { this.legalIndexVersion = legalIndexVersion; }
    public String getSourceDigest() { return sourceDigest; }
    public void setSourceDigest(String sourceDigest) { this.sourceDigest = sourceDigest; }
    public String getPayloadCiphertext() { return payloadCiphertext; }
    public void setPayloadCiphertext(String payloadCiphertext) { this.payloadCiphertext = payloadCiphertext; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
