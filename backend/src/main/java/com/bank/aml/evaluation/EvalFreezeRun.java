package com.bank.aml.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 隐藏 TEST 冻结运行记录：freezeId 唯一，保证同一冻结基线只正式执行一次，避免反复挑选最好结果。
 */
@Entity
@Table(name = "eval_freeze_run")
public class EvalFreezeRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String freezeId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(length = 64)
    private String runId;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String aggregateJson;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    public Long getId() {
        return id;
    }

    public String getFreezeId() {
        return freezeId;
    }

    public void setFreezeId(String freezeId) {
        this.freezeId = freezeId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getAggregateJson() {
        return aggregateJson;
    }

    public void setAggregateJson(String aggregateJson) {
        this.aggregateJson = aggregateJson;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
