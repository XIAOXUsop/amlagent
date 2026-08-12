package com.bank.aml.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 人工复核记录：保留 Agent 原始结论，不覆盖。
 */
@Entity
@Table(name = "manual_review")
public class ManualReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(length = 64)
    private String reviewerId;

    /** Agent 最终评级 */
    @Column(length = 32)
    private String agentRiskLevel;

    /** Guardrails 修正后评级 */
    @Column(length = 32)
    private String guardrailRiskLevel;

    /** 复核人评级 */
    @Column(length = 32)
    private String reviewerRiskLevel;

    /** 决定：APPROVE（批准）/ REJECT（驳回，需补充尽调）/ ESCALATE（升级） */
    @Column(length = 32)
    private String decision;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCaseId() {
        return caseId;
    }

    public void setCaseId(Long caseId) {
        this.caseId = caseId;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getAgentRiskLevel() {
        return agentRiskLevel;
    }

    public void setAgentRiskLevel(String agentRiskLevel) {
        this.agentRiskLevel = agentRiskLevel;
    }

    public String getGuardrailRiskLevel() {
        return guardrailRiskLevel;
    }

    public void setGuardrailRiskLevel(String guardrailRiskLevel) {
        this.guardrailRiskLevel = guardrailRiskLevel;
    }

    public String getReviewerRiskLevel() {
        return reviewerRiskLevel;
    }

    public void setReviewerRiskLevel(String reviewerRiskLevel) {
        this.reviewerRiskLevel = reviewerRiskLevel;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
