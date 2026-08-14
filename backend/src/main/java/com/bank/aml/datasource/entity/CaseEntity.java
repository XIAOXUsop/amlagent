package com.bank.aml.datasource.entity;

import com.bank.aml.common.enums.CaseStatus;
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

/**
 * 反洗钱预警工单。
 */
@Entity
@Table(name = "aml_case")
public class CaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String customerId;

    @Column(length = 64)
    private String customerName;

    /** 触发预警的规则描述 */
    @Column(length = 255)
    private String alertRule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CaseStatus status = CaseStatus.PENDING;

    /** 最终风险评级（Guardrail 后）：低风险 / 中风险 / 高风险 */
    @Column(length = 32)
    private String riskLevel;

    /** 模型原始风险评级（Guardrail 前），用于分层审计 */
    @Column(length = 32)
    private String rawRiskLevel;

    /** 尽调报告（JSON 序列化） */
    @Column(columnDefinition = "TEXT")
    private String reportJson;

    @Column(length = 255)
    private String summary;

    /** 报告来源：AGENT / RULE_FALLBACK */
    @Column(length = 32)
    private String reportSource;

    /** 尽调快照 ID（用于追溯 Agent 与 Guardrail 使用的数据版本） */
    @Column(length = 64)
    private String snapshotId;

    // ---- 可靠执行控制（P1）----
    /** 执行版本：每次抢占自增，用于幂等 */
    @Column(nullable = false)
    private int executionVersion = 0;

    /** 人工复核版本：每次复核决策自增，用于人工决策乐观锁（与 executionVersion 语义分离） */
    @Column(nullable = false)
    private int reviewRevision = 0;

    /** 当前执行者（Worker 标识） */
    @Column(length = 64)
    private String lockedBy;

    /** 执行加锁时间 */
    private LocalDateTime lockedAt;

    /** 最近心跳时间（长模型调用期间周期性刷新，用于区分"崩溃"与"慢任务"） */
    private LocalDateTime heartbeatAt;

    /** 累计重试次数 */
    @Column(nullable = false)
    private int retryCount = 0;

    /** 下次重试时间（RETRY_WAIT 状态下的指数退避调度） */
    private LocalDateTime nextRetryAt;

    /** 最近失败码 */
    @Column(length = 64)
    private String failureCode;

    /** 最近失败原因 */
    @Column(length = 512)
    private String failureMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getAlertRule() {
        return alertRule;
    }

    public void setAlertRule(String alertRule) {
        this.alertRule = alertRule;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public void setStatus(CaseStatus status) {
        this.status = status;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getRawRiskLevel() {
        return rawRiskLevel;
    }

    public void setRawRiskLevel(String rawRiskLevel) {
        this.rawRiskLevel = rawRiskLevel;
    }

    public String getReportJson() {
        return reportJson;
    }

    public void setReportJson(String reportJson) {
        this.reportJson = reportJson;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getReportSource() {
        return reportSource;
    }

    public void setReportSource(String reportSource) {
        this.reportSource = reportSource;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public int getExecutionVersion() {
        return executionVersion;
    }

    public void setExecutionVersion(int executionVersion) {
        this.executionVersion = executionVersion;
    }

    public int getReviewRevision() {
        return reviewRevision;
    }

    public void setReviewRevision(int reviewRevision) {
        this.reviewRevision = reviewRevision;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public LocalDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(LocalDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }

    public LocalDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(LocalDateTime heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(LocalDateTime nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
