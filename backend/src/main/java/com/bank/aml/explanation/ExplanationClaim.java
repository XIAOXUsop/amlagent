package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 待验证事实 Claim（v3 计划 §6）：六问题与材料之间的桥梁。
 * C1 主体与账户、C2 真实付款义务、C3 代付授权、C4 实际执行、C5 商业合理性、C6 反证与剩余未知。
 * 材料只说明某个来源提供了什么信息；核验动作说明如何检查；Claim 说明这些信息为什么足够或不足。
 *
 * <p>状态语义：SUPPORTED 表示"按已记录方法和限制，分析员认为证据支持"，
 * 不表示系统保证事实为真。C1~C4 在集团代付政策中不可整体跳过（NOT_APPLICABLE 需政策明确允许）。
 */
@Entity
@Table(name = "explanation_claim")
public class ExplanationClaim {

    /** 事实编号（C1~C6）。 */
    @Column(nullable = false, length = 8)
    private String claimCode;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    private Long unitId;

    /** UNASSESSED / SUPPORTED / CONTRADICTED / UNRESOLVED / NOT_APPLICABLE。 */
    @Column(nullable = false, length = 24)
    private String status;

    /** DECISION_CRITICAL / CONTEXT。 */
    @Column(nullable = false, length = 24)
    private String importance;

    /** 主体引用（JSON：付款人/买方/收款方账户与 KYC 标识；不落敏感正文）。 */
    @Column(length = 500)
    private String subjectRefs;

    /** 关联交易（sourceRecordId 列表，逗号分隔）。 */
    @Column(length = 500)
    private String transactionIds;

    /** 关联订单/授权编号。 */
    @Column(length = 500)
    private String orderRefs;

    /** 人工判断：为什么这些信息足够或不足（至少 10 个字符）。 */
    @Column(nullable = false, length = 2000)
    private String judgement;

    /** 已记录的核验方法。 */
    @Column(length = 1000)
    private String methodNote;

    /** 判断的局限（来源限制、未覆盖范围）。 */
    @Column(length = 1000)
    private String limitations;

    /** NOT_APPLICABLE 时的理由与适用条件（仅政策明确允许的事实可填）。 */
    @Column(length = 1000)
    private String notApplicableReason;

    @Column(nullable = false)
    private int claimRevision;

    @Column(nullable = false, length = 64)
    private String updatedBy;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long value) { unitId = value; }
    public String getClaimCode() { return claimCode; }
    public void setClaimCode(String value) { claimCode = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getImportance() { return importance; }
    public void setImportance(String value) { importance = value; }
    public String getSubjectRefs() { return subjectRefs; }
    public void setSubjectRefs(String value) { subjectRefs = value; }
    public String getTransactionIds() { return transactionIds; }
    public void setTransactionIds(String value) { transactionIds = value; }
    public String getOrderRefs() { return orderRefs; }
    public void setOrderRefs(String value) { orderRefs = value; }
    public String getJudgement() { return judgement; }
    public void setJudgement(String value) { judgement = value; }
    public String getMethodNote() { return methodNote; }
    public void setMethodNote(String value) { methodNote = value; }
    public String getLimitations() { return limitations; }
    public void setLimitations(String value) { limitations = value; }
    public String getNotApplicableReason() { return notApplicableReason; }
    public void setNotApplicableReason(String value) { notApplicableReason = value; }
    public int getClaimRevision() { return claimRevision; }
    public void setClaimRevision(int value) { claimRevision = value; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String value) { updatedBy = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
}
