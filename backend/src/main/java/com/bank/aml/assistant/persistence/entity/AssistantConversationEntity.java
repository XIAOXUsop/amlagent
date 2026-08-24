package com.bank.aml.assistant.persistence.entity;

import com.bank.aml.assistant.domain.AssistantConversationStatus;
import com.bank.aml.datasource.entity.CustomerEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "assistant_conversation")
public class AssistantConversationEntity {
    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 128)
    private String operatorUsername;

    @Column(nullable = false)
    private Long customerId;

    @Column(nullable = false, length = 32)
    private String customerNoAtCreation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AssistantConversationStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Version
    @Column(nullable = false)
    private long version;

    public static AssistantConversationEntity create(String operatorUsername, CustomerEntity customer,
                                                      LocalDateTime expiresAt) {
        AssistantConversationEntity entity = new AssistantConversationEntity();
        entity.id = UUID.randomUUID().toString();
        entity.operatorUsername = operatorUsername;
        entity.customerId = customer.getId();
        entity.customerNoAtCreation = customer.getCustomerNo();
        entity.status = AssistantConversationStatus.ACTIVE;
        entity.expiresAt = expiresAt;
        entity.createdAt = LocalDateTime.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public void touch() { updatedAt = LocalDateTime.now(); }
    public void archive() { status = AssistantConversationStatus.ARCHIVED; touch(); }
    public void expire() { status = AssistantConversationStatus.EXPIRED; touch(); }
    public boolean isActive() { return status == AssistantConversationStatus.ACTIVE && expiresAt.isAfter(LocalDateTime.now()); }

    public String getId() { return id; }
    public String getOperatorUsername() { return operatorUsername; }
    public Long getCustomerId() { return customerId; }
    public String getCustomerNoAtCreation() { return customerNoAtCreation; }
    public AssistantConversationStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public long getVersion() { return version; }
}
