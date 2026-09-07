package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 问题重要性降级双人确认记录（验收 A5-04）。
 * 降级采用提案-确认两步流程：分析员创建提案；另一位已认证 REVIEWER/ADMIN 在独立请求中确认。
 * 确认身份由服务端从认证上下文写入；客户端不再具有指定 confirmedBy 的权威意义。
 */
@Entity
@Table(name = "explanation_issue_review")
public class ExplanationIssueReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private Long issueId;

    /** 创建提案时的问题版本；确认时必须仍一致（防并发处置覆盖）。 */
    @Column(nullable = false)
    private int proposalRevision;

    /** 提案时问题版本（别名语义：与 proposalRevision 同源，记录提案所依据的问题版本）。 */
    @Column(nullable = false)
    private int issueRevision;

    @Column(nullable = false, length = 32)
    private IssueSeverity originalSeverity;

    @Column(nullable = false, length = 32)
    private IssueSeverity proposedSeverity;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(length = 160)
    private String evidenceReference;

    @Column(nullable = false, length = 64)
    private String proposedBy;

    @Column(nullable = false)
    private LocalDateTime proposedAt;

    /** PENDING / CONFIRMED / REJECTED。 */
    @Column(nullable = false, length = 24)
    private String status;

    @Column(length = 64)
    private String confirmedBy;

    private LocalDateTime confirmedAt;

    @Column(length = 500)
    private String confirmNote;

    @Column(length = 500)
    private String rejectedReason;

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public Long getIssueId() { return issueId; }
    public void setIssueId(Long value) { issueId = value; }
    public int getProposalRevision() { return proposalRevision; }
    public void setProposalRevision(int value) { proposalRevision = value; }
    public int getIssueRevision() { return issueRevision; }
    public void setIssueRevision(int value) { issueRevision = value; }
    public IssueSeverity getOriginalSeverity() { return originalSeverity; }
    public void setOriginalSeverity(IssueSeverity value) { originalSeverity = value; }
    public IssueSeverity getProposedSeverity() { return proposedSeverity; }
    public void setProposedSeverity(IssueSeverity value) { proposedSeverity = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getEvidenceReference() { return evidenceReference; }
    public void setEvidenceReference(String value) { evidenceReference = value; }
    public String getProposedBy() { return proposedBy; }
    public void setProposedBy(String value) { proposedBy = value; }
    public LocalDateTime getProposedAt() { return proposedAt; }
    public void setProposedAt(LocalDateTime value) { proposedAt = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String value) { confirmedBy = value; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime value) { confirmedAt = value; }
    public String getConfirmNote() { return confirmNote; }
    public void setConfirmNote(String value) { confirmNote = value; }
    public String getRejectedReason() { return rejectedReason; }
    public void setRejectedReason(String value) { rejectedReason = value; }
}
