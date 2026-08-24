package com.bank.aml.assistant.persistence.entity;

import com.bank.aml.assistant.domain.AssistantDigests;
import com.bank.aml.assistant.domain.AssistantMessageRole;
import com.bank.aml.assistant.domain.AssistantMessageStatus;
import com.bank.aml.assistant.domain.AssistantResultType;
import com.bank.aml.security.SensitivePayloadCipher;
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
@Table(name = "assistant_message")
public class AssistantMessageEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String conversationId;

    @Column(nullable = false)
    private long sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssistantMessageRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssistantMessageStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private AssistantResultType resultType;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String contentCiphertext;

    @Column(nullable = false, length = 64, columnDefinition = "char(64)")
    private String contentDigest;

    @Column(length = 64)
    private String clientMessageId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    public static AssistantMessageEntity user(String conversationId, long sequenceNo,
                                              String clientMessageId, String content) {
        return create(conversationId, sequenceNo, AssistantMessageRole.USER,
                AssistantMessageStatus.ACCEPTED, clientMessageId, content);
    }

    public static AssistantMessageEntity assistantPlaceholder(String conversationId, long sequenceNo) {
        return create(conversationId, sequenceNo, AssistantMessageRole.ASSISTANT,
                AssistantMessageStatus.PROCESSING, null, "");
    }

    private static AssistantMessageEntity create(String conversationId, long sequenceNo,
                                                 AssistantMessageRole role, AssistantMessageStatus status,
                                                 String clientMessageId, String content) {
        AssistantMessageEntity entity = new AssistantMessageEntity();
        entity.id = UUID.randomUUID().toString();
        entity.conversationId = conversationId;
        entity.sequenceNo = sequenceNo;
        entity.role = role;
        entity.status = status;
        entity.clientMessageId = clientMessageId;
        entity.setContent(content);
        entity.createdAt = LocalDateTime.now();
        return entity;
    }

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public void setContent(String content) {
        String safe = content == null ? "" : content;
        this.contentCiphertext = SensitivePayloadCipher.encrypt(safe);
        this.contentDigest = AssistantDigests.sha256(safe);
    }

    public String content() { return SensitivePayloadCipher.decrypt(contentCiphertext); }

    public void complete(String content, AssistantResultType resultType) {
        requirePendingAssistant();
        setContent(content);
        this.resultType = resultType;
        this.status = AssistantMessageStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void refuse(String content, AssistantResultType resultType) {
        requirePendingAssistant();
        setContent(content);
        this.resultType = resultType;
        this.status = AssistantMessageStatus.REFUSED;
        this.completedAt = LocalDateTime.now();
    }

    public void fail(String content, AssistantResultType resultType) {
        requirePendingAssistant();
        setContent(content);
        this.resultType = resultType;
        this.status = AssistantMessageStatus.FAILED;
        this.completedAt = LocalDateTime.now();
    }

    public void block(String content) {
        requirePendingAssistant();
        setContent(content);
        this.resultType = AssistantResultType.OUTPUT_BLOCKED;
        this.status = AssistantMessageStatus.BLOCKED;
        this.completedAt = LocalDateTime.now();
    }

    private void requirePendingAssistant() {
        if (role != AssistantMessageRole.ASSISTANT || status != AssistantMessageStatus.PROCESSING) {
            throw new IllegalStateException("仅可终结待处理的 AI 消息");
        }
    }

    public String getId() { return id; }
    public String getConversationId() { return conversationId; }
    public long getSequenceNo() { return sequenceNo; }
    public AssistantMessageRole getRole() { return role; }
    public AssistantMessageStatus getStatus() { return status; }
    public AssistantResultType getResultType() { return resultType; }
    public String getContentCiphertext() { return contentCiphertext; }
    public String getContentDigest() { return contentDigest; }
    public String getClientMessageId() { return clientMessageId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}
