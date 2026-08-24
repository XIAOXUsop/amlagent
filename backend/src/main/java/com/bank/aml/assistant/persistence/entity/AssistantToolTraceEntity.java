package com.bank.aml.assistant.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "assistant_tool_trace")
public class AssistantToolTraceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 36) private String runId;
    @Column(nullable = false) private long sequenceNo;
    @Column(nullable = false, length = 64) private String toolName;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false) private long durationMs;
    @Column(length = 64, columnDefinition = "char(64)") private String resultDigest;
    @Column(columnDefinition = "TEXT") private String evidenceIdsJson;
    @Column(length = 64) private String errorCode;
    @Column(nullable = false) private LocalDateTime createdAt;

    public static AssistantToolTraceEntity create(String runId, long sequenceNo, String toolName, String status,
                                                  long durationMs, String resultDigest, String evidenceIdsJson,
                                                  String errorCode) {
        AssistantToolTraceEntity entity = new AssistantToolTraceEntity();
        entity.runId = runId;
        entity.sequenceNo = sequenceNo;
        entity.toolName = toolName;
        entity.status = status;
        entity.durationMs = durationMs;
        entity.resultDigest = resultDigest;
        entity.evidenceIdsJson = evidenceIdsJson;
        entity.errorCode = errorCode;
        entity.createdAt = LocalDateTime.now();
        return entity;
    }

    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
    public Long getId() { return id; }
    public String getRunId() { return runId; }
    public long getSequenceNo() { return sequenceNo; }
    public String getToolName() { return toolName; }
    public String getStatus() { return status; }
    public long getDurationMs() { return durationMs; }
    public String getResultDigest() { return resultDigest; }
    public String getEvidenceIdsJson() { return evidenceIdsJson; }
    public String getErrorCode() { return errorCode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
