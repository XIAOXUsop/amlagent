package com.bank.aml.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_outbox")
public class AuditOutboxEvent {

    public enum Status {

        PENDING, PROCESSED

    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 160)
    private String eventKey;

    @Column(nullable = false, length = 64)
    private String actor;

    @Column(nullable = false, length = 64)
    private String actionName;

    @Column(length = 64)
    private String targetType;

    @Column(length = 64)
    private String targetId;

    @Column(nullable = false, length = 16)
    private String outcome;

    @Column(length = 256)
    private String detail;

    @Column(length = 64)
    private String clientIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime processedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null)
            createdAt = LocalDateTime.now(Clock.systemUTC());
        if (status == null)
            status = Status.PENDING;
    }

    public Long getId() {
        return id;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String value) {
        eventKey = value;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String value) {
        actor = value;
    }

    public String getActionName() {
        return actionName;
    }

    public void setActionName(String value) {
        actionName = value;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String value) {
        targetType = value;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String value) {
        targetId = value;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String value) {
        outcome = value;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String value) {
        detail = value;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String value) {
        clientIp = value;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status value) {
        status = value;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime value) {
        processedAt = value;
    }

}
