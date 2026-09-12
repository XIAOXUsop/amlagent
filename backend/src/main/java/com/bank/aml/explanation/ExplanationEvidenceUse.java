package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 材料在解释中的使用记录：全部同案；显式版本；用于受影响单元反查（v2 计划 §12）。 */
@Entity
@Table(name = "explanation_evidence_use")
public class ExplanationEvidenceUse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long submissionId;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false, length = 8)
    private String questionCode;

    private Long artifactVersionId;

    private Long verificationEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExplanationEvidenceDirection direction;

    @Column(length = 255)
    private String location;

    @Column(length = 500)
    private String transactionIds;

    @Column(length = 500)
    private String note;

    public Long getId() {
        return id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(Long value) {
        submissionId = value;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long value) {
        caseId = value;
    }

    public String getQuestionCode() {
        return questionCode;
    }

    public void setQuestionCode(String value) {
        questionCode = value;
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

    public ExplanationEvidenceDirection getDirection() {
        return direction;
    }

    public void setDirection(ExplanationEvidenceDirection value) {
        direction = value;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String value) {
        location = value;
    }

    public String getTransactionIds() {
        return transactionIds;
    }

    public void setTransactionIds(String value) {
        transactionIds = value;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String value) {
        note = value;
    }

}
