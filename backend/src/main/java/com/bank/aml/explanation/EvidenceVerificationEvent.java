package com.bank.aml.explanation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 核验动作记录：追加不可变；技术解析与人工作用判断分开（v2 计划 §5.1/§11.3）。 */
@Entity
@Table(name = "evidence_verification_event")
public class EvidenceVerificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long caseId;

    @Column(nullable = false)
    private Long artifactVersionId;

    /** 核验方法（如 CORE_BANKING_DELIVERY_RECORD_CHECK / SITE_VISIT）。 */
    @Column(nullable = false, length = 64)
    private String method;

    /** 观察到的事实（含系统/方式与适用期间）。 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String observedFacts;

    @Column(length = 1000)
    private String limitations;

    /** CONFIRMED / MISMATCH / UNRESOLVED。 */
    @Column(nullable = false, length = 32)
    private String result;

    @Column(nullable = false, length = 64)
    private String actor;

    private Long previousEventId;

    @Column(nullable = false)
    private LocalDateTime eventTime;

    public Long getId() { return id; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long value) { caseId = value; }
    public Long getArtifactVersionId() { return artifactVersionId; }
    public void setArtifactVersionId(Long value) { artifactVersionId = value; }
    public String getMethod() { return method; }
    public void setMethod(String value) { method = value; }
    public String getObservedFacts() { return observedFacts; }
    public void setObservedFacts(String value) { observedFacts = value; }
    public String getLimitations() { return limitations; }
    public void setLimitations(String value) { limitations = value; }
    public String getResult() { return result; }
    public void setResult(String value) { result = value; }
    public String getActor() { return actor; }
    public void setActor(String value) { actor = value; }
    public Long getPreviousEventId() { return previousEventId; }
    public void setPreviousEventId(Long value) { previousEventId = value; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime value) { eventTime = value; }
}
