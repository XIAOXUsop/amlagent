package com.bank.aml.investigation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.LocalDateTime;

@Entity
@Table(name = "investigation_hypothesis")
public class InvestigationHypothesis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false, length = 64)
    private String scenarioCode;

    @Column(nullable = false, length = 96)
    private String hypothesisCode;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 500)
    private String investigationQuestion;

    @Column(nullable = false, length = 500)
    private String requiredEvidenceTypes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private HypothesisStatus status;

    @Column(length = 2000)
    private String rationale;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false, length = 64)
    private String createdBy;

    @Column(length = 64)
    private String updatedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        if (status == null)
            status = HypothesisStatus.OPEN;
        if (createdAt == null)
            createdAt = now;
        if (updatedAt == null)
            updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(Clock.systemUTC());
    }

    public Long getId() {
        return id;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long value) {
        caseId = value;
    }

    public String getScenarioCode() {
        return scenarioCode;
    }

    public void setScenarioCode(String value) {
        scenarioCode = value;
    }

    public String getHypothesisCode() {
        return hypothesisCode;
    }

    public void setHypothesisCode(String value) {
        hypothesisCode = value;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String value) {
        title = value;
    }

    public String getInvestigationQuestion() {
        return investigationQuestion;
    }

    public void setInvestigationQuestion(String value) {
        investigationQuestion = value;
    }

    public String getRequiredEvidenceTypes() {
        return requiredEvidenceTypes;
    }

    public void setRequiredEvidenceTypes(String value) {
        requiredEvidenceTypes = value;
    }

    public HypothesisStatus getStatus() {
        return status;
    }

    public void setStatus(HypothesisStatus value) {
        status = value;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String value) {
        rationale = value;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int value) {
        revision = value;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String value) {
        createdBy = value;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String value) {
        updatedBy = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

}
