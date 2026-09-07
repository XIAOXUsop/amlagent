package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 问题（差异/反证/缺口）登记（v2 计划 §6/§12）。
 * 稳定 issueKey；待分派新材料挂案件级（unitId=NULL）；不可静默删除。
 */
@Entity
@Table(name = "explanation_issue")
public class ExplanationIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    /** NULL 表示案件级问题（如待分派新材料）。 */
    private Long unitId;

    @Column(nullable = false, length = 96)
    private String issueKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IssueSeverity severity;

    @Column(length = 8)
    private String questionCode;

    @Column(length = 500)
    private String transactionIds;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IssueDisposition disposition;

    @Column(length = 1000)
    private String dispositionReason;

    @Column(length = 64)
    private String resolvedBy;

    private LocalDateTime resolvedAt;

    /** 重要性降级时的独立复核确认人（与处理人不同）。 */
    @Column(length = 64)
    private String confirmedBy;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false, length = 64)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long value) { unitId = value; }
    public String getIssueKey() { return issueKey; }
    public void setIssueKey(String value) { issueKey = value; }
    public IssueSeverity getSeverity() { return severity; }
    public void setSeverity(IssueSeverity value) { severity = value; }
    public String getQuestionCode() { return questionCode; }
    public void setQuestionCode(String value) { questionCode = value; }
    public String getTransactionIds() { return transactionIds; }
    public void setTransactionIds(String value) { transactionIds = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public IssueDisposition getDisposition() { return disposition; }
    public void setDisposition(IssueDisposition value) { disposition = value; }
    public String getDispositionReason() { return dispositionReason; }
    public void setDispositionReason(String value) { dispositionReason = value; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String value) { resolvedBy = value; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime value) { resolvedAt = value; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String value) { confirmedBy = value; }
    public int getRevision() { return revision; }
    public void setRevision(int value) { revision = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
