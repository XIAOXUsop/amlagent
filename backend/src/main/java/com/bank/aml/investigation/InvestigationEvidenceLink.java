package com.bank.aml.investigation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.LocalDateTime;

@Entity
@Table(name = "investigation_evidence_link")
public class InvestigationEvidenceLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long hypothesisId;

    @Column(nullable = false)
    private Long caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private InvestigationEvidenceType evidenceType;

    @Column(nullable = false, length = 160)
    private String evidenceReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EvidenceStance stance;

    @Column(nullable = false, length = 1000)
    private String findingSummary;

    @Column(nullable = false, length = 64)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null)
            createdAt = LocalDateTime.now(Clock.systemUTC());
    }

    public Long getId() {
        return id;
    }

    public Long getHypothesisId() {
        return hypothesisId;
    }

    public void setHypothesisId(Long value) {
        hypothesisId = value;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long value) {
        caseId = value;
    }

    public InvestigationEvidenceType getEvidenceType() {
        return evidenceType;
    }

    public void setEvidenceType(InvestigationEvidenceType value) {
        evidenceType = value;
    }

    public String getEvidenceReference() {
        return evidenceReference;
    }

    public void setEvidenceReference(String value) {
        evidenceReference = value;
    }

    public EvidenceStance getStance() {
        return stance;
    }

    public void setStance(EvidenceStance value) {
        stance = value;
    }

    public String getFindingSummary() {
        return findingSummary;
    }

    public void setFindingSummary(String value) {
        findingSummary = value;
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

}
