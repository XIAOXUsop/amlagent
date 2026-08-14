package com.bank.aml.tools;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 生产工具调用轨迹持久化实体（对应 V2 迁移的 tool_execution_trace 表）。
 * <p>不保存参数明文（姓名/证件号/法规 query），只保存结果摘要哈希与 evidenceIds JSON。
 */
@Entity
@Table(name = "tool_execution_trace")
public class ToolExecutionTraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private int executionVersion;

    @Column(nullable = false, length = 64)
    private String snapshotId;

    @Column(nullable = false)
    private long sequenceNo;

    @Column(nullable = false, length = 64)
    private String toolName;

    @Column(nullable = false)
    private boolean requested;

    @Column(nullable = false)
    private boolean executed;

    @Column(nullable = false)
    private boolean success;

    @Column(nullable = false)
    private boolean argumentValid;

    @Column(nullable = false)
    private long durationMs;

    @Column(length = 64)
    private String resultDigest;

    @Column(columnDefinition = "TEXT")
    private String evidenceIdsJson;

    @Column(length = 64)
    private String errorCode;

    @Column(nullable = false)
    private LocalDateTime createdAt;

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

    public int getExecutionVersion() {
        return executionVersion;
    }

    public void setExecutionVersion(int executionVersion) {
        this.executionVersion = executionVersion;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }

    public long getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(long sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public boolean isRequested() {
        return requested;
    }

    public void setRequested(boolean requested) {
        this.requested = requested;
    }

    public boolean isExecuted() {
        return executed;
    }

    public void setExecuted(boolean executed) {
        this.executed = executed;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public boolean isArgumentValid() {
        return argumentValid;
    }

    public void setArgumentValid(boolean argumentValid) {
        this.argumentValid = argumentValid;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getResultDigest() {
        return resultDigest;
    }

    public void setResultDigest(String resultDigest) {
        this.resultDigest = resultDigest;
    }

    public String getEvidenceIdsJson() {
        return evidenceIdsJson;
    }

    public void setEvidenceIdsJson(String evidenceIdsJson) {
        this.evidenceIdsJson = evidenceIdsJson;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
