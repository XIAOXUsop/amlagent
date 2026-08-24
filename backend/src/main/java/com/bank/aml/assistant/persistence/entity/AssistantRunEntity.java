package com.bank.aml.assistant.persistence.entity;

import com.bank.aml.assistant.domain.AssistantRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "assistant_run")
public class AssistantRunEntity {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String conversationId;
    @Column(nullable = false, length = 36) private String userMessageId;
    @Column(nullable = false, length = 36) private String assistantMessageId;
    @Column(length = 64) private String snapshotId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private AssistantRunStatus status;
    @Column(length = 32) private String intent;
    @Column(length = 64) private String modelProvider;
    @Column(length = 128) private String modelName;
    @Column(length = 64) private String promptVersion;
    @Column(length = 64, columnDefinition = "char(64)") private String sourceDigest;
    private LocalDateTime asOfTime;
    private Long inputTokens;
    private Long outputTokens;
    private Long durationMs;
    @Column(length = 64) private String failureCode;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public static AssistantRunEntity accepted(String conversationId, String userMessageId, String assistantMessageId) {
        AssistantRunEntity entity = new AssistantRunEntity();
        entity.id = UUID.randomUUID().toString();
        entity.conversationId = conversationId;
        entity.userMessageId = userMessageId;
        entity.assistantMessageId = assistantMessageId;
        entity.status = AssistantRunStatus.ACCEPTED;
        entity.createdAt = LocalDateTime.now();
        return entity;
    }

    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
    public void processing(String intent) {
        requireStatus(AssistantRunStatus.ACCEPTED);
        this.intent = intent;
        this.status = AssistantRunStatus.PROCESSING;
    }
    public void attachSnapshot(String snapshotId, String digest, LocalDateTime asOfTime) {
        requireStatus(AssistantRunStatus.PROCESSING);
        this.snapshotId = snapshotId; this.sourceDigest = digest; this.asOfTime = asOfTime;
    }
    public void model(String provider, String name, String promptVersion) {
        this.modelProvider = provider; this.modelName = name; this.promptVersion = promptVersion;
    }
    public void complete(long durationMs, Long inputTokens, Long outputTokens) {
        requireStatus(AssistantRunStatus.PROCESSING);
        this.status = AssistantRunStatus.COMPLETED; this.durationMs = durationMs;
        this.inputTokens = inputTokens; this.outputTokens = outputTokens; this.completedAt = LocalDateTime.now();
    }
    public void terminal(AssistantRunStatus status, String failureCode, long durationMs) {
        if (status == AssistantRunStatus.ACCEPTED || status == AssistantRunStatus.PROCESSING) {
            throw new IllegalArgumentException("终态状态无效");
        }
        if (this.status != AssistantRunStatus.ACCEPTED && this.status != AssistantRunStatus.PROCESSING) {
            throw new IllegalStateException("run 已经处于终态: " + this.status);
        }
        this.status = status; this.failureCode = failureCode; this.durationMs = durationMs;
        this.completedAt = LocalDateTime.now();
    }

    private void requireStatus(AssistantRunStatus expected) {
        if (status != expected) throw new IllegalStateException("run 状态转换非法: " + status + " -> " + expected);
    }

    public String getId() { return id; }
    public String getConversationId() { return conversationId; }
    public String getUserMessageId() { return userMessageId; }
    public String getAssistantMessageId() { return assistantMessageId; }
    public String getSnapshotId() { return snapshotId; }
    public AssistantRunStatus getStatus() { return status; }
    public String getIntent() { return intent; }
    public String getModelProvider() { return modelProvider; }
    public String getModelName() { return modelName; }
    public String getPromptVersion() { return promptVersion; }
    public String getSourceDigest() { return sourceDigest; }
    public LocalDateTime getAsOfTime() { return asOfTime; }
    public Long getInputTokens() { return inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public Long getDurationMs() { return durationMs; }
    public String getFailureCode() { return failureCode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}
