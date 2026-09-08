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

    /** 服务器冻结的预警命中交易（G1-1/V34：sourceRecordId JSON 数组；调查进度不定义全集）。 */
    @Column(name = "trigger_transaction_ids", columnDefinition = "TEXT")
    private String triggerTransactionIds;

    /** 辅助交易（上下文，不计入命中全集）。 */
    @Column(name = "auxiliary_transaction_ids", columnDefinition = "TEXT")
    private String auxiliaryTransactionIds;

    /** 范围来源版本（监测批次/上游版本；变更可检测）。 */
    @Column(name = "scope_source_version", length = 64)
    private String scopeSourceVersion;

    @Column(name = "scope_frozen_at")
    private LocalDateTime scopeFrozenAt;

    @Column(name = "scope_frozen_by", length = 64)
    private String scopeFrozenBy;

    public String getTriggerTransactionIds() { return triggerTransactionIds; }
    public void setTriggerTransactionIds(String value) { triggerTransactionIds = value; }
    public String getAuxiliaryTransactionIds() { return auxiliaryTransactionIds; }
    public void setAuxiliaryTransactionIds(String value) { auxiliaryTransactionIds = value; }
    public String getScopeSourceVersion() { return scopeSourceVersion; }
    public void setScopeSourceVersion(String value) { scopeSourceVersion = value; }
    public LocalDateTime getScopeFrozenAt() { return scopeFrozenAt; }
    public void setScopeFrozenAt(LocalDateTime value) { scopeFrozenAt = value; }
    public String getScopeFrozenBy() { return scopeFrozenBy; }
    public void setScopeFrozenBy(String value) { scopeFrozenBy = value; }

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
