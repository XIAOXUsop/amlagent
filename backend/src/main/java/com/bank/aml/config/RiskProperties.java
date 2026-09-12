package com.bank.aml.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 交易风险事实、规则缓存、降级报告和名单匹配的统一策略参数。 */
@ConfigurationProperties(prefix = "aml.risk")
@Validated
public class RiskProperties {

    @Min(1)
    @Max(86_400)
    private long ruleCacheTtlSeconds = 60;

    @Valid
    @NotNull
    private Transaction transaction = new Transaction();

    @Valid
    @NotNull
    private Sanction sanction = new Sanction();

    public long getRuleCacheTtlSeconds() {
        return ruleCacheTtlSeconds;
    }

    public void setRuleCacheTtlSeconds(long ruleCacheTtlSeconds) {
        this.ruleCacheTtlSeconds = ruleCacheTtlSeconds;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }

    public Sanction getSanction() {
        return sanction;
    }

    public void setSanction(Sanction sanction) {
        this.sanction = sanction;
    }

    public static class Transaction {

        @DecimalMin(value = "0", inclusive = false)
        private BigDecimal largeAmount = new BigDecimal("1000000");

        @DecimalMin(value = "0", inclusive = false)
        private BigDecimal highlightedAmount = new BigDecimal("500000");

        @Min(2)
        @Max(100)
        private int repeatedAmountCount = 5;

        @Min(0)
        @Max(23)
        private int nightStartHour = 22;

        @Min(0)
        @Max(23)
        private int nightEndHour = 6;

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private double elevatedCrossBorderRatio = 20;

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        private double elevatedNightRatio = 30;

        @Min(1)
        @Max(3_650)
        private int shortWindowDays = 30;

        @Min(1)
        @Max(3_650)
        private int mediumWindowDays = 90;

        @Min(1)
        @Max(3_650)
        private int longWindowDays = 180;

        @Min(1)
        @Max(100)
        private int topCounterpartyLimit = 5;

        @AssertTrue(message = "highlighted amount must not exceed the large-transaction amount")
        public boolean isAmountThresholdOrderValid() {
            return highlightedAmount != null && largeAmount != null && highlightedAmount.compareTo(largeAmount) <= 0;
        }

        @AssertTrue(message = "night window start and end must differ")
        public boolean isNightWindowValid() {
            return nightStartHour != nightEndHour;
        }

        @AssertTrue(message = "transaction windows must be strictly increasing")
        public boolean isWindowOrderValid() {
            return shortWindowDays < mediumWindowDays && mediumWindowDays < longWindowDays;
        }

        public BigDecimal getLargeAmount() {
            return largeAmount;
        }

        public void setLargeAmount(BigDecimal largeAmount) {
            this.largeAmount = largeAmount;
        }

        public BigDecimal getHighlightedAmount() {
            return highlightedAmount;
        }

        public void setHighlightedAmount(BigDecimal highlightedAmount) {
            this.highlightedAmount = highlightedAmount;
        }

        public int getRepeatedAmountCount() {
            return repeatedAmountCount;
        }

        public void setRepeatedAmountCount(int repeatedAmountCount) {
            this.repeatedAmountCount = repeatedAmountCount;
        }

        public int getNightStartHour() {
            return nightStartHour;
        }

        public void setNightStartHour(int nightStartHour) {
            this.nightStartHour = nightStartHour;
        }

        public int getNightEndHour() {
            return nightEndHour;
        }

        public void setNightEndHour(int nightEndHour) {
            this.nightEndHour = nightEndHour;
        }

        public double getElevatedCrossBorderRatio() {
            return elevatedCrossBorderRatio;
        }

        public void setElevatedCrossBorderRatio(double elevatedCrossBorderRatio) {
            this.elevatedCrossBorderRatio = elevatedCrossBorderRatio;
        }

        public double getElevatedNightRatio() {
            return elevatedNightRatio;
        }

        public void setElevatedNightRatio(double elevatedNightRatio) {
            this.elevatedNightRatio = elevatedNightRatio;
        }

        public int getShortWindowDays() {
            return shortWindowDays;
        }

        public void setShortWindowDays(int shortWindowDays) {
            this.shortWindowDays = shortWindowDays;
        }

        public int getMediumWindowDays() {
            return mediumWindowDays;
        }

        public void setMediumWindowDays(int mediumWindowDays) {
            this.mediumWindowDays = mediumWindowDays;
        }

        public int getLongWindowDays() {
            return longWindowDays;
        }

        public void setLongWindowDays(int longWindowDays) {
            this.longWindowDays = longWindowDays;
        }

        public int getTopCounterpartyLimit() {
            return topCounterpartyLimit;
        }

        public void setTopCounterpartyLimit(int topCounterpartyLimit) {
            this.topCounterpartyLimit = topCounterpartyLimit;
        }

    }

    public static class Sanction {

        @Min(0)
        @Max(100)
        private int identityExactScore = 100;

        @Min(0)
        @Max(100)
        private int identityConflictWithNameScore = 30;

        @Min(0)
        @Max(100)
        private int identityConflictScore = 10;

        @Min(0)
        @Max(100)
        private int subjectTypeConflictScore = 35;

        @Min(0)
        @Max(100)
        private int exactNameScore = 88;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double highNameSimilarity = 0.88;

        @Min(0)
        @Max(100)
        private int highNameSimilarityScore = 72;

        @Min(0)
        @Max(100)
        private int containedNameScore = 55;

        @AssertTrue(message = "sanction confidence scores must decrease with weaker evidence")
        public boolean isScoreOrderValid() {
            return identityExactScore >= exactNameScore && exactNameScore >= highNameSimilarityScore
                    && highNameSimilarityScore >= containedNameScore
                    && identityConflictWithNameScore >= identityConflictScore;
        }

        public int getIdentityExactScore() {
            return identityExactScore;
        }

        public void setIdentityExactScore(int identityExactScore) {
            this.identityExactScore = identityExactScore;
        }

        public int getIdentityConflictWithNameScore() {
            return identityConflictWithNameScore;
        }

        public void setIdentityConflictWithNameScore(int value) {
            this.identityConflictWithNameScore = value;
        }

        public int getIdentityConflictScore() {
            return identityConflictScore;
        }

        public void setIdentityConflictScore(int identityConflictScore) {
            this.identityConflictScore = identityConflictScore;
        }

        public int getSubjectTypeConflictScore() {
            return subjectTypeConflictScore;
        }

        public void setSubjectTypeConflictScore(int subjectTypeConflictScore) {
            this.subjectTypeConflictScore = subjectTypeConflictScore;
        }

        public int getExactNameScore() {
            return exactNameScore;
        }

        public void setExactNameScore(int exactNameScore) {
            this.exactNameScore = exactNameScore;
        }

        public double getHighNameSimilarity() {
            return highNameSimilarity;
        }

        public void setHighNameSimilarity(double highNameSimilarity) {
            this.highNameSimilarity = highNameSimilarity;
        }

        public int getHighNameSimilarityScore() {
            return highNameSimilarityScore;
        }

        public void setHighNameSimilarityScore(int highNameSimilarityScore) {
            this.highNameSimilarityScore = highNameSimilarityScore;
        }

        public int getContainedNameScore() {
            return containedNameScore;
        }

        public void setContainedNameScore(int containedNameScore) {
            this.containedNameScore = containedNameScore;
        }

    }

}
