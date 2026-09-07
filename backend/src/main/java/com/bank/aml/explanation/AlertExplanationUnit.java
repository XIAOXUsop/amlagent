package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 预警核验单元：一条有效预警一个单元；当前指针更新受案件锁保护（v2 计划 §3.1/§12）。 */
@Entity
@Table(name = "alert_explanation_unit")
public class AlertExplanationUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private Long alertId;

    /** 所属假设（案件内一个假设下的单元）；由预警归并时的剧本映射决定。 */
    private Long hypothesisId;

    /** 适用配方：GOODS_SETTLED_V1 / GOODS_PREPAY_V1；NULL 表示分析员尚未通过草稿核定适用性。 */
    @Column(length = 48)
    private String policyCode;

    /** 服务端冻结的预警范围版本（requiredAlertIds / 触发交易映射）。 */
    @Column(nullable = false)
    private int scopeRevision;

    /** 六问题草稿（JSON）；自动保存只改变草稿，不产生最终业务判断。 */
    @Column(columnDefinition = "TEXT")
    private String draftJson;

    @Column(nullable = false)
    private int draftRevision;

    /** 草稿编辑人追加记录（用于实质贡献人派生，逗号分隔）。 */
    @Column(length = 1000)
    private String editors;

    /** 当前采用的不可变提交；NULL 表示尚无提交。 */
    private Long currentSubmissionId;

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
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long value) { alertId = value; }
    public Long getHypothesisId() { return hypothesisId; }
    public void setHypothesisId(Long value) { hypothesisId = value; }
    public String getPolicyCode() { return policyCode; }
    public void setPolicyCode(String value) { policyCode = value; }
    public int getScopeRevision() { return scopeRevision; }
    public void setScopeRevision(int value) { scopeRevision = value; }
    public String getDraftJson() { return draftJson; }
    public void setDraftJson(String value) { draftJson = value; }
    public int getDraftRevision() { return draftRevision; }
    public void setDraftRevision(int value) { draftRevision = value; }
    public String getEditors() { return editors; }
    public void setEditors(String value) { editors = value; }
    public Long getCurrentSubmissionId() { return currentSubmissionId; }
    public void setCurrentSubmissionId(Long value) { currentSubmissionId = value; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String value) { createdBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
