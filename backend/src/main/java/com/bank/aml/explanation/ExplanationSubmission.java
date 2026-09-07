package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 不可变单元提交（v2 计划 §9）：一次人工提交冻结问题、事实、材料、未知与建议。
 * payload 一经写入不得修改；状态变化（WITHDRAWN/STALE/SUPERSEDED）通过字段与审计事件表达。
 */
@Entity
@Table(name = "explanation_submission")
public class ExplanationSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long unitId;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private int submissionNo;

    /** 冻结的六问题/建议/未知/贡献人（JSON），不可变。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ExplanationOutcome outcome;

    @Column(nullable = false)
    private boolean suspicionBasisComplete;

    @Column(nullable = false)
    private boolean criticalUnknown;

    @Column(nullable = false)
    private boolean unresolvedDisclosed;

    @Column(nullable = false)
    private boolean followupRequired;

    /** 提交时绑定的核验依据版本。 */
    private Long basisId;

    /** 提交内容摘要：固定排序后的范围、问题、材料/核验版本、政策和声明未知。 */
    @Column(nullable = false, length = 64)
    private String inputDigest;

    /** 幂等键（动作:操作者:目标:请求摘要）。 */
    @Column(length = 96)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SubmissionState state;

    /** 服务端派生的实质贡献人集合（逗号分隔），不接受客户端自报空集合。 */
    @Column(length = 1000)
    private String contributors;

    @Column(nullable = false, length = 64)
    private String submittedBy;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    @Column(length = 255)
    private String supersededReason;

    public Long getId() { return id; }
    public Long getUnitId() { return unitId; }
    public void setUnitId(Long value) { unitId = value; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public int getSubmissionNo() { return submissionNo; }
    public void setSubmissionNo(int value) { submissionNo = value; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String value) { payloadJson = value; }
    public ExplanationOutcome getOutcome() { return outcome; }
    public void setOutcome(ExplanationOutcome value) { outcome = value; }
    public boolean isSuspicionBasisComplete() { return suspicionBasisComplete; }
    public void setSuspicionBasisComplete(boolean value) { suspicionBasisComplete = value; }
    public boolean isCriticalUnknown() { return criticalUnknown; }
    public void setCriticalUnknown(boolean value) { criticalUnknown = value; }
    public boolean isUnresolvedDisclosed() { return unresolvedDisclosed; }
    public void setUnresolvedDisclosed(boolean value) { unresolvedDisclosed = value; }
    public boolean isFollowupRequired() { return followupRequired; }
    public void setFollowupRequired(boolean value) { followupRequired = value; }
    public Long getBasisId() { return basisId; }
    public void setBasisId(Long value) { basisId = value; }
    public String getInputDigest() { return inputDigest; }
    public void setInputDigest(String value) { inputDigest = value; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
    public SubmissionState getState() { return state; }
    public void setState(SubmissionState value) { state = value; }
    public String getContributors() { return contributors; }
    public void setContributors(String value) { contributors = value; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String value) { submittedBy = value; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime value) { submittedAt = value; }
    public String getSupersededReason() { return supersededReason; }
    public void setSupersededReason(String value) { supersededReason = value; }
}
