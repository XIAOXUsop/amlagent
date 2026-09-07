package com.bank.aml.datasource.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 平台敏感操作审计记录（审批决策/客户管理/死信重放/登录事件/调试动作）。
 * <p>detail 只保存短摘要与计数，绝不写入评论文本、密码或客户自由输入明文。</p>
 */
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(length = 160, unique = true) private String eventKey;
    @Column(nullable = false, length = 64) private String actor;
    @Column(nullable = false, length = 64) private String action;
    @Column(length = 64) private String targetType;
    @Column(length = 64) private String targetId;
    /** SUCCESS / FAILURE */
    @Column(nullable = false, length = 16) private String outcome;
    /** 短摘要：decision 码、计数、失败原因码等，不含自由文本明文 */
    @Column(length = 256) private String detail;
    @Column(length = 64) private String clientIp;
    @Column(nullable = false) private LocalDateTime occurredAt;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) occurredAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String v) { eventKey = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { actor = v; }
    public String getAction() { return action; }
    public void setAction(String v) { action = v; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String v) { targetType = v; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String v) { targetId = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { outcome = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { detail = v; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String v) { clientIp = v; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime v) { occurredAt = v; }
}
