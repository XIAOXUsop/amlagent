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
@Table(name = "alert_investigation_coverage")
public class AlertInvestigationCoverage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private Long alertId;
    @Column(nullable = false) private Long caseId;
    private Long hypothesisId;
    /** 覆盖决定形成时锁定的假设版本；NULL 表示存量数据没有可证明的版本绑定，门禁会要求重新确认。 */
    private Long hypothesisRevision;
    /** v2 解释核验：采用的单 元不可变提交；最终校验必须存在且属于当前案件/预警/政策。 */
    @Column(name = "unit_submission_id")
    private Long unitSubmissionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AlertCoverageConclusion conclusion;
    @Column(length = 1000) private String analysisSummary;
    @Column(nullable = false) private int revision;
    @Column(length = 64) private String updatedBy;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;

    @PrePersist void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (conclusion == null) conclusion = AlertCoverageConclusion.PENDING;
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long value) { alertId = value; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public Long getHypothesisId() { return hypothesisId; }
    public void setHypothesisId(Long value) { hypothesisId = value; }
    public Long getHypothesisRevision() { return hypothesisRevision; }
    public void setHypothesisRevision(Long value) { hypothesisRevision = value; }
    public Long getUnitSubmissionId() { return unitSubmissionId; }
    public void setUnitSubmissionId(Long value) { unitSubmissionId = value; }
    public AlertCoverageConclusion getConclusion() { return conclusion; }
    public void setConclusion(AlertCoverageConclusion value) { conclusion = value; }
    public String getAnalysisSummary() { return analysisSummary; }
    public void setAnalysisSummary(String value) { analysisSummary = value; }
    public int getRevision() { return revision; }
    public void setRevision(int value) { revision = value; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String value) { updatedBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
