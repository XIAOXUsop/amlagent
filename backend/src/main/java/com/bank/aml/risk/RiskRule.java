package com.bank.aml.risk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 风险规则（配置化，带版本/优先级/生效时间）。
 */
@Entity
@Table(name = "risk_rule")
public class RiskRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则编码，如 SANCTION_LEVEL_1 */
    @Column(nullable = false, unique = true, length = 64)
    private String ruleCode;

    @Column(length = 128)
    private String ruleName;

    @Column(nullable = false)
    private int version = 1;

    /** 优先级：数值越小越先执行 */
    @Column(nullable = false)
    private int priority = 100;

    /** 条件表达式（简单 DSL）：sanction.maxSeverity == 1 && transaction.crossRatio > 20 */
    @Column(length = 255)
    private String conditionExpression;

    /** 命中后目标评级：高风险 / 中风险 / 低风险 */
    @Column(length = 32)
    private String targetRiskLevel;

    /** 动作：MANUAL_REVIEW（强制转人工）/ AUTO_DONE */
    @Column(length = 32)
    private String action = "AUTO_DONE";

    @Column(nullable = false)
    private boolean enabled = true;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }

    public void setConditionExpression(String conditionExpression) {
        this.conditionExpression = conditionExpression;
    }

    public String getTargetRiskLevel() {
        return targetRiskLevel;
    }

    public void setTargetRiskLevel(String targetRiskLevel) {
        this.targetRiskLevel = targetRiskLevel;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDateTime effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDateTime getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDateTime effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
