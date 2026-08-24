package com.bank.aml.assistant.persistence.entity;

import com.bank.aml.security.SensitivePayloadCipher;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "assistant_snapshot")
public class AssistantSnapshotEntity {
    @Id @Column(length = 64) private String snapshotId;
    @Column(nullable = false, length = 36) private String runId;
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT") private String payloadCiphertext;
    @Column(nullable = false, length = 64, columnDefinition = "char(64)") private String sourceDigest;
    @Column(nullable = false, length = 64) private String sourceSystem;
    @Column(nullable = false, length = 128) private String sourceVersion;
    @Column(nullable = false, length = 128) private String knowledgeIndexVersion;
    @Column(nullable = false) private LocalDateTime asOfTime;
    @Column(nullable = false) private LocalDateTime createdAt;

    public static AssistantSnapshotEntity create(String snapshotId, String runId, String payload,
                                                 String sourceDigest, String sourceSystem, String sourceVersion,
                                                 String knowledgeIndexVersion, LocalDateTime asOfTime) {
        AssistantSnapshotEntity entity = new AssistantSnapshotEntity();
        entity.snapshotId = snapshotId;
        entity.runId = runId;
        entity.payloadCiphertext = SensitivePayloadCipher.encrypt(payload);
        entity.sourceDigest = sourceDigest;
        entity.sourceSystem = sourceSystem;
        entity.sourceVersion = sourceVersion;
        entity.knowledgeIndexVersion = knowledgeIndexVersion;
        entity.asOfTime = asOfTime;
        entity.createdAt = LocalDateTime.now();
        return entity;
    }

    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
    public String payload() { return SensitivePayloadCipher.decrypt(payloadCiphertext); }
    public String getSnapshotId() { return snapshotId; }
    public String getRunId() { return runId; }
    public String getPayloadCiphertext() { return payloadCiphertext; }
    public String getSourceDigest() { return sourceDigest; }
    public String getSourceSystem() { return sourceSystem; }
    public String getSourceVersion() { return sourceVersion; }
    public String getKnowledgeIndexVersion() { return knowledgeIndexVersion; }
    public LocalDateTime getAsOfTime() { return asOfTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
