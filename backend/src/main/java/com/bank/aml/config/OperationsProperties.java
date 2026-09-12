package com.bank.aml.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 风险优先运营队列的评分版本与分阶段 SLA。 */
@ConfigurationProperties(prefix = "aml.operations")
@Validated
public class OperationsProperties {

    @NotBlank
    private String policyVersion = "P1_V1";

    @Min(0)
    @Max(100)
    private int baseScore = 20;

    @NotNull
    private Map<String, @Min(0) @Max(100) Integer> scenarioWeights = defaultScenarioWeights();

    @Min(0)
    @Max(100)
    private int additionalAlertWeight = 10;

    @Min(0)
    @Max(100)
    private int additionalAlertMaxBonus = 20;

    @Min(0)
    @Max(100)
    private int highRiskMinimum = 60;

    @Min(0)
    @Max(100)
    private int highRiskBonus = 30;

    @Min(0)
    @Max(100)
    private int mediumRiskBonus = 15;

    @Min(0)
    @Max(100)
    private int reportPendingMinimum = 85;

    @Min(0)
    @Max(100)
    private int reportPendingBonus = 20;

    @Min(0)
    @Max(100)
    private int failedCaseBonus = 15;

    @Min(1)
    @Max(100)
    private int maximumScore = 100;

    @Min(1)
    @Max(100)
    private int criticalThreshold = 85;

    @Min(1)
    @Max(100)
    private int highThreshold = 60;

    @Min(1)
    @Max(100)
    private int mediumThreshold = 35;

    @Valid
    @NotNull
    private Sla sla = new Sla();

    @AssertTrue(message = "priority thresholds must satisfy maximum >= critical > high > medium")
    public boolean isPriorityThresholdOrderValid() {
        return maximumScore >= criticalThreshold && criticalThreshold > highThreshold
                && highThreshold > mediumThreshold;
    }

    public int scenarioWeight(String scenario) {
        return scenarioWeights.getOrDefault(scenario, 0);
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public int getBaseScore() {
        return baseScore;
    }

    public void setBaseScore(int baseScore) {
        this.baseScore = baseScore;
    }

    public Map<String, Integer> getScenarioWeights() {
        return scenarioWeights;
    }

    public void setScenarioWeights(Map<String, Integer> scenarioWeights) {
        this.scenarioWeights = scenarioWeights;
    }

    public int getAdditionalAlertWeight() {
        return additionalAlertWeight;
    }

    public void setAdditionalAlertWeight(int additionalAlertWeight) {
        this.additionalAlertWeight = additionalAlertWeight;
    }

    public int getAdditionalAlertMaxBonus() {
        return additionalAlertMaxBonus;
    }

    public void setAdditionalAlertMaxBonus(int additionalAlertMaxBonus) {
        this.additionalAlertMaxBonus = additionalAlertMaxBonus;
    }

    public int getHighRiskMinimum() {
        return highRiskMinimum;
    }

    public void setHighRiskMinimum(int highRiskMinimum) {
        this.highRiskMinimum = highRiskMinimum;
    }

    public int getHighRiskBonus() {
        return highRiskBonus;
    }

    public void setHighRiskBonus(int highRiskBonus) {
        this.highRiskBonus = highRiskBonus;
    }

    public int getMediumRiskBonus() {
        return mediumRiskBonus;
    }

    public void setMediumRiskBonus(int mediumRiskBonus) {
        this.mediumRiskBonus = mediumRiskBonus;
    }

    public int getReportPendingMinimum() {
        return reportPendingMinimum;
    }

    public void setReportPendingMinimum(int reportPendingMinimum) {
        this.reportPendingMinimum = reportPendingMinimum;
    }

    public int getReportPendingBonus() {
        return reportPendingBonus;
    }

    public void setReportPendingBonus(int reportPendingBonus) {
        this.reportPendingBonus = reportPendingBonus;
    }

    public int getFailedCaseBonus() {
        return failedCaseBonus;
    }

    public void setFailedCaseBonus(int failedCaseBonus) {
        this.failedCaseBonus = failedCaseBonus;
    }

    public int getMaximumScore() {
        return maximumScore;
    }

    public void setMaximumScore(int maximumScore) {
        this.maximumScore = maximumScore;
    }

    public int getCriticalThreshold() {
        return criticalThreshold;
    }

    public void setCriticalThreshold(int criticalThreshold) {
        this.criticalThreshold = criticalThreshold;
    }

    public int getHighThreshold() {
        return highThreshold;
    }

    public void setHighThreshold(int highThreshold) {
        this.highThreshold = highThreshold;
    }

    public int getMediumThreshold() {
        return mediumThreshold;
    }

    public void setMediumThreshold(int mediumThreshold) {
        this.mediumThreshold = mediumThreshold;
    }

    public Sla getSla() {
        return sla;
    }

    public void setSla(Sla sla) {
        this.sla = sla;
    }

    private static Map<String, Integer> defaultScenarioWeights() {
        Map<String, Integer> weights = new LinkedHashMap<>();
        weights.put("SANCTIONS_WATCHLIST", 80);
        weights.put("STRUCTURING", 50);
        weights.put("RAPID_MOVEMENT", 45);
        weights.put("CROSS_BORDER_ANOMALY", 45);
        weights.put("COMPLEX_OWNERSHIP", 35);
        weights.put("PROFILE_MISMATCH", 15);
        return weights;
    }

    public static class Sla {

        @Min(1)
        private int investigationCriticalHours = 4;

        @Min(1)
        private int investigationHighHours = 12;

        @Min(1)
        private int investigationMediumHours = 24;

        @Min(1)
        private int investigationNormalHours = 72;

        @Min(1)
        private int reviewCriticalHours = 2;

        @Min(1)
        private int reviewHighHours = 4;

        @Min(1)
        private int reviewMediumHours = 8;

        @Min(1)
        private int reviewNormalHours = 24;

        @Min(1)
        private int reportCriticalHours = 4;

        @Min(1)
        private int reportHighHours = 8;

        @Min(1)
        private int reportMediumHours = 24;

        @Min(1)
        private int reportNormalHours = 48;

        public int investigationHours(String priority) {
            return switch (priority) {
                case "CRITICAL" -> investigationCriticalHours;
                case "HIGH" -> investigationHighHours;
                case "MEDIUM" -> investigationMediumHours;
                default -> investigationNormalHours;
            };
        }

        public int reviewHours(String priority) {
            return switch (priority) {
                case "CRITICAL" -> reviewCriticalHours;
                case "HIGH" -> reviewHighHours;
                case "MEDIUM" -> reviewMediumHours;
                default -> reviewNormalHours;
            };
        }

        public int reportHours(String priority) {
            return switch (priority) {
                case "CRITICAL" -> reportCriticalHours;
                case "HIGH" -> reportHighHours;
                case "MEDIUM" -> reportMediumHours;
                default -> reportNormalHours;
            };
        }

        public int getInvestigationCriticalHours() {
            return investigationCriticalHours;
        }

        public void setInvestigationCriticalHours(int value) {
            this.investigationCriticalHours = value;
        }

        public int getInvestigationHighHours() {
            return investigationHighHours;
        }

        public void setInvestigationHighHours(int value) {
            this.investigationHighHours = value;
        }

        public int getInvestigationMediumHours() {
            return investigationMediumHours;
        }

        public void setInvestigationMediumHours(int value) {
            this.investigationMediumHours = value;
        }

        public int getInvestigationNormalHours() {
            return investigationNormalHours;
        }

        public void setInvestigationNormalHours(int value) {
            this.investigationNormalHours = value;
        }

        public int getReviewCriticalHours() {
            return reviewCriticalHours;
        }

        public void setReviewCriticalHours(int value) {
            this.reviewCriticalHours = value;
        }

        public int getReviewHighHours() {
            return reviewHighHours;
        }

        public void setReviewHighHours(int value) {
            this.reviewHighHours = value;
        }

        public int getReviewMediumHours() {
            return reviewMediumHours;
        }

        public void setReviewMediumHours(int value) {
            this.reviewMediumHours = value;
        }

        public int getReviewNormalHours() {
            return reviewNormalHours;
        }

        public void setReviewNormalHours(int value) {
            this.reviewNormalHours = value;
        }

        public int getReportCriticalHours() {
            return reportCriticalHours;
        }

        public void setReportCriticalHours(int value) {
            this.reportCriticalHours = value;
        }

        public int getReportHighHours() {
            return reportHighHours;
        }

        public void setReportHighHours(int value) {
            this.reportHighHours = value;
        }

        public int getReportMediumHours() {
            return reportMediumHours;
        }

        public void setReportMediumHours(int value) {
            this.reportMediumHours = value;
        }

        public int getReportNormalHours() {
            return reportNormalHours;
        }

        public void setReportNormalHours(int value) {
            this.reportNormalHours = value;
        }

    }

}
