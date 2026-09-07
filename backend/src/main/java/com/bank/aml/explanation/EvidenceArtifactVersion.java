package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 材料证据版本（v2 计划 §11.3）：正文不可更新；撤销/变更通过追加版本与事件表达。
 * 保存实际取得内容的哈希与声称哈希；哈希一致只说明内容一致，不自动授予“业务真实”。
 */
@Entity
@Table(name = "evidence_artifact_version")
public class EvidenceArtifactVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false, length = 96)
    private String artifactKey;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 64)
    private String sourceSystem;

    /** 不透明来源引用：不接受任意 URL。 */
    @Column(nullable = false, length = 160)
    private String sourceReference;

    @Column(length = 255)
    private String contentLocation;

    /** 实际取得内容哈希。 */
    @Column(nullable = false, length = 64)
    private String contentSha256;

    /** 来源声称的内容哈希；与实际不一致 → integrity=MISMATCH（完整性问题）。 */
    @Column(length = 64)
    private String claimedSha256;

    /** RESOLVED / UNAVAILABLE / NOT_FOUND / FORBIDDEN。 */
    @Column(nullable = false, length = 24)
    private String availability;

    /** MATCH / MISMATCH / NOT_CHECKED。 */
    @Column(nullable = false, length = 24)
    private String integrityStatus;

    private LocalDateTime periodFrom;

    private LocalDateTime periodTo;

    @Column(length = 500)
    private String sourceChain;

    @Column(nullable = false, length = 64)
    private String capturedBy;

    @Column(nullable = false)
    private LocalDateTime capturedAt;

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public String getArtifactKey() { return artifactKey; }
    public void setArtifactKey(String value) { artifactKey = value; }
    public int getVersion() { return version; }
    public void setVersion(int value) { version = value; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String value) { sourceSystem = value; }
    public String getSourceReference() { return sourceReference; }
    public void setSourceReference(String value) { sourceReference = value; }
    public String getContentLocation() { return contentLocation; }
    public void setContentLocation(String value) { contentLocation = value; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String value) { contentSha256 = value; }
    public String getClaimedSha256() { return claimedSha256; }
    public void setClaimedSha256(String value) { claimedSha256 = value; }
    public String getAvailability() { return availability; }
    public void setAvailability(String value) { availability = value; }
    public String getIntegrityStatus() { return integrityStatus; }
    public void setIntegrityStatus(String value) { integrityStatus = value; }
    public LocalDateTime getPeriodFrom() { return periodFrom; }
    public void setPeriodFrom(LocalDateTime value) { periodFrom = value; }
    public LocalDateTime getPeriodTo() { return periodTo; }
    public void setPeriodTo(LocalDateTime value) { periodTo = value; }
    public String getSourceChain() { return sourceChain; }
    public void setSourceChain(String value) { sourceChain = value; }
    public String getCapturedBy() { return capturedBy; }
    public void setCapturedBy(String value) { capturedBy = value; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime value) { capturedAt = value; }
}
