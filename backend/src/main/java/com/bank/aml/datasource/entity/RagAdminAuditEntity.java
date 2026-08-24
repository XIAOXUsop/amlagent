package com.bank.aml.datasource.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "rag_admin_audit")
public class RagAdminAuditEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 128) private String actor;
    @Column(nullable = false, length = 64) private String actionName;
    @Column(length = 64) private String targetVersion;
    @Column(nullable = false, length = 32) private String outcome;
    @Column(length = 128) private String detailCode;
    @Column(nullable = false) private LocalDateTime occurredAt;
    public Long getId() { return id; }
    public String getActor() { return actor; }
    public void setActor(String v) { actor = v; }
    public String getActionName() { return actionName; }
    public void setActionName(String v) { actionName = v; }
    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String v) { targetVersion = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { outcome = v; }
    public String getDetailCode() { return detailCode; }
    public void setDetailCode(String v) { detailCode = v; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime v) { occurredAt = v; }
}
