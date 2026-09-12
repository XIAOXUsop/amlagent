package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 事实-证据关联（v3 计划 §9）：claim → 材料版本/核验事件。 同时保留反证（SUPPORTS/CHALLENGES）；sourceFamily
 * 标注同源家族（同源多文件不累计独立确认）； 定位必须属于相应内容版本。
 */
@Entity
@Table(name = "claim_evidence_link")
public class ClaimEvidenceLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private Long claimId;

    private Long artifactVersionId;

    private Long verificationEventId;

    /** SUPPORTS / CHALLENGES / CONTEXT。 */
    @Column(nullable = false, length = 32)
    private String direction;

    /** 来源家族（sourceSystem + 逻辑文档身份）：同家族重复登记不累计独立性。 */
    @Column(length = 96)
    private String sourceFamily;

    @Column(length = 255)
    private String location;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, length = 64)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long value) {
        caseId = value;
    }

    public Long getClaimId() {
        return claimId;
    }

    public void setClaimId(Long value) {
        claimId = value;
    }

    public Long getArtifactVersionId() {
        return artifactVersionId;
    }

    public void setArtifactVersionId(Long value) {
        artifactVersionId = value;
    }

    public Long getVerificationEventId() {
        return verificationEventId;
    }

    public void setVerificationEventId(Long value) {
        verificationEventId = value;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String value) {
        direction = value;
    }

    public String getSourceFamily() {
        return sourceFamily;
    }

    public void setSourceFamily(String value) {
        sourceFamily = value;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String value) {
        location = value;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String value) {
        note = value;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String value) {
        createdBy = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime value) {
        createdAt = value;
    }

}
