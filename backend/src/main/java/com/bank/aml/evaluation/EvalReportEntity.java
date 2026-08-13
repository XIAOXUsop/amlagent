package com.bank.aml.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 评测报告记录，用于版本对比与历史回溯。
 */
@Entity
@Table(name = "eval_report")
public class EvalReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 评测类型：RULE_REGRESSION / AGENT / RAG */
    @Column(nullable = false, length = 32)
    private String evalType;

    /** 版本标识 */
    @Column(length = 64)
    private String versionTag;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String metricsJson;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getEvalType() {
        return evalType;
    }

    public void setEvalType(String evalType) {
        this.evalType = evalType;
    }

    public String getVersionTag() {
        return versionTag;
    }

    public void setVersionTag(String versionTag) {
        this.versionTag = versionTag;
    }

    public String getMetricsJson() {
        return metricsJson;
    }

    public void setMetricsJson(String metricsJson) {
        this.metricsJson = metricsJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
