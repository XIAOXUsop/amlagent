package com.bank.aml.sanction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 制裁候选的追加式人工核验记录。 */
@Entity
@Table(name = "sanction_candidate_review")
public class SanctionCandidateReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 32) private String customerId;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String candidateFingerprint;
    @Column(nullable = false, length = 128) private String candidateName;
    @Column(nullable = false, length = 64) private String listType;
    @Column(nullable = false) private int matchScore;
    @Column(nullable = false, length = 32) private String algorithmDecision;
    @Column(nullable = false, length = 32) private String reviewDecision;
    @Column(nullable = false, length = 64) private String reviewerId;
    @Column(name = "comment_text", length = 500) private String comment;
    @Column(nullable = false) private int reviewRevision;
    @Column(nullable = false) private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCandidateFingerprint() { return candidateFingerprint; }
    public void setCandidateFingerprint(String candidateFingerprint) { this.candidateFingerprint = candidateFingerprint; }
    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String candidateName) { this.candidateName = candidateName; }
    public String getListType() { return listType; }
    public void setListType(String listType) { this.listType = listType; }
    public int getMatchScore() { return matchScore; }
    public void setMatchScore(int matchScore) { this.matchScore = matchScore; }
    public String getAlgorithmDecision() { return algorithmDecision; }
    public void setAlgorithmDecision(String algorithmDecision) { this.algorithmDecision = algorithmDecision; }
    public String getReviewDecision() { return reviewDecision; }
    public void setReviewDecision(String reviewDecision) { this.reviewDecision = reviewDecision; }
    public String getReviewerId() { return reviewerId; }
    public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public int getReviewRevision() { return reviewRevision; }
    public void setReviewRevision(int reviewRevision) { this.reviewRevision = reviewRevision; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
