package com.bank.aml.review;

import com.bank.aml.domain.EddTaskPurpose;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/** 一轮补充尽调任务；只保存内部证据引用，不在此表保存附件或敏感材料正文。 */
@Entity
@Table(name = "enhanced_due_diligence_request")
public class EnhancedDueDiligenceRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private int roundNo;

    @Column(nullable = false, length = 64)
    private String reasonCode;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String requiredItemsJson;

    @Column(nullable = false, length = 64)
    private String requestedBy;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    @Column(length = 64)
    private String assignedTo;

    @Column(length = 64)
    private String assignedUnit;

    @Column(nullable = false)
    private LocalDateTime dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EnhancedDueDiligenceStatus status;

    @Column(nullable = false)
    private int revision;

    @Column(columnDefinition = "TEXT")
    private String responseSummary;

    @Column(columnDefinition = "TEXT")
    private String evidenceReferencesJson;

    @Column(length = 64)
    private String respondedBy;

    private LocalDateTime respondedAt;

    private LocalDateTime resolvedAt;

    /** 明确完成核验的复核人（A5-08：与 respondedBy 不同人，身份由服务端认证写入）。 */
    @Column(length = 64)
    private String resolvedBy;

    @Column(length = 64)
    private String cancelledBy;

    private LocalDateTime cancelledAt;

    @Column(length = 500)
    private String cancellationReason;

    /** 任务目的：DECISION_SUPPORT=当前判断补件；CONTINUING_REVIEW=决定后持续核验（v2 计划 §8.2）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EddTaskPurpose purpose = EddTaskPurpose.DECISION_SUPPORT;

    /** 义务接续来源：原任务/原复核；消费时按实际 ID 而非“最近一轮”。 */
    private Long originRequestId;

    private Long originReviewId;

    /** 关联问题（issueKey/issueId 列表）与完成标准的 JSON。 */
    @Column(columnDefinition = "TEXT")
    private String issueBindings;

    /** 截止时间所用工作日历版本；不同日历不得改变历史截止时间。 */
    @Column(length = 64)
    private String dueCalendarVersion;

    /** 显式完成/接替原因：RESOLVED 不再由其他业务决定自动产生。 */
    @Column(length = 500)
    private String resolutionReason;

    /** 完成标准（A5-07）：接续任务的核验目标与验收口径完整值；长度审计不能替代保存。 */
    @Column(columnDefinition = "TEXT")
    private String completionStandard;

    /** 义务事实键（FR-03/V33）：本任务承接的具体义务（如 DELIVERY:PO-001）；错绑视为未覆盖。 */
    @Column(name = "obligation_fact_key", length = 96)
    private String obligationFactKey;

    /** 义务关联金额（定点十进制；可选）。 */
    @Column(name = "obligation_amount", precision = 20, scale = 2)
    private BigDecimal obligationAmount;

    /** 义务关联交易（sourceRecordId 逗号分隔；可选）。 */
    @Column(name = "obligation_transaction_ids", length = 500)
    private String obligationTransactionIds;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(Clock.systemUTC());
        if (createdAt == null)
            createdAt = now;
        if (updatedAt == null)
            updatedAt = now;
        if (requestedAt == null)
            requestedAt = now;
        if (status == null)
            status = EnhancedDueDiligenceStatus.OPEN;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(Clock.systemUTC());
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

    public int getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(int roundNo) {
        this.roundNo = roundNo;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getRequiredItemsJson() {
        return requiredItemsJson;
    }

    public void setRequiredItemsJson(String requiredItemsJson) {
        this.requiredItemsJson = requiredItemsJson;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(String assignedTo) {
        this.assignedTo = assignedTo;
    }

    public String getAssignedUnit() {
        return assignedUnit;
    }

    public void setAssignedUnit(String assignedUnit) {
        this.assignedUnit = assignedUnit;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public void setDueAt(LocalDateTime dueAt) {
        this.dueAt = dueAt;
    }

    public EnhancedDueDiligenceStatus getStatus() {
        return status;
    }

    public void setStatus(EnhancedDueDiligenceStatus status) {
        this.status = status;
    }

    public int getRevision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = revision;
    }

    public String getResponseSummary() {
        return responseSummary;
    }

    public void setResponseSummary(String responseSummary) {
        this.responseSummary = responseSummary;
    }

    public String getEvidenceReferencesJson() {
        return evidenceReferencesJson;
    }

    public void setEvidenceReferencesJson(String evidenceReferencesJson) {
        this.evidenceReferencesJson = evidenceReferencesJson;
    }

    public String getRespondedBy() {
        return respondedBy;
    }

    public void setRespondedBy(String respondedBy) {
        this.respondedBy = respondedBy;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(LocalDateTime respondedAt) {
        this.respondedAt = respondedAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(String cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public void setCancellationReason(String cancellationReason) {
        this.cancellationReason = cancellationReason;
    }

    public EddTaskPurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(EddTaskPurpose value) {
        purpose = value;
    }

    public Long getOriginRequestId() {
        return originRequestId;
    }

    public void setOriginRequestId(Long value) {
        originRequestId = value;
    }

    public Long getOriginReviewId() {
        return originReviewId;
    }

    public void setOriginReviewId(Long value) {
        originReviewId = value;
    }

    public String getIssueBindings() {
        return issueBindings;
    }

    public void setIssueBindings(String value) {
        issueBindings = value;
    }

    public String getDueCalendarVersion() {
        return dueCalendarVersion;
    }

    public void setDueCalendarVersion(String value) {
        dueCalendarVersion = value;
    }

    public String getResolutionReason() {
        return resolutionReason;
    }

    public void setResolutionReason(String value) {
        resolutionReason = value;
    }

    public String getCompletionStandard() {
        return completionStandard;
    }

    public void setCompletionStandard(String value) {
        completionStandard = value;
    }

    public String getObligationFactKey() {
        return obligationFactKey;
    }

    public void setObligationFactKey(String value) {
        obligationFactKey = value;
    }

    public BigDecimal getObligationAmount() {
        return obligationAmount;
    }

    public void setObligationAmount(BigDecimal value) {
        obligationAmount = value;
    }

    public String getObligationTransactionIds() {
        return obligationTransactionIds;
    }

    public void setObligationTransactionIds(String value) {
        obligationTransactionIds = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

}
