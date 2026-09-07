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

import java.time.LocalDateTime;

@Entity
@Table(name = "aml_alert")
public class AmlAlert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 64) private String externalAlertId;
    @Column(nullable = false, length = 32) private String customerId;
    @Column(nullable = false, length = 64) private String ruleCode;
    @Column(nullable = false, length = 64) private String scenarioCode;
    @Column(nullable = false, length = 500) private String hitReason;
    @Column(nullable = false) private LocalDateTime occurredAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AlertStatus status;
    private Long caseId;
    @Column(nullable = false) private int revision;
    @Column(length = 500) private String resolutionReason;
    @Column(nullable = false, length = 64) private String createdBy;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;

    @PrePersist void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (occurredAt == null) occurredAt = now;
        if (status == null) status = AlertStatus.NEW;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public String getExternalAlertId() { return externalAlertId; }
    public void setExternalAlertId(String value) { externalAlertId = value; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String value) { customerId = value; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String value) { ruleCode = value; }
    public String getScenarioCode() { return scenarioCode; }
    public void setScenarioCode(String value) { scenarioCode = value; }
    public String getHitReason() { return hitReason; }
    public void setHitReason(String value) { hitReason = value; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime value) { occurredAt = value; }
    public AlertStatus getStatus() { return status; }
    public void setStatus(AlertStatus value) { status = value; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public int getRevision() { return revision; }
    public void setRevision(int value) { revision = value; }
    public String getResolutionReason() { return resolutionReason; }
    public void setResolutionReason(String value) { resolutionReason = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
