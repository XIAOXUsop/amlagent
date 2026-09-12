package com.bank.aml.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 解释核验工作区的提醒与重复义务策略。 */
@ConfigurationProperties(prefix = "aml.explanation")
@Validated
public class ExplanationProperties {

    @Min(0)
    @Max(90)
    private int dueSoonDays = 5;

    @Min(1)
    @Max(20)
    private int repeatedEvidenceRequestCount = 2;

    public int getDueSoonDays() {
        return dueSoonDays;
    }

    public void setDueSoonDays(int dueSoonDays) {
        this.dueSoonDays = dueSoonDays;
    }

    public int getRepeatedEvidenceRequestCount() {
        return repeatedEvidenceRequestCount;
    }

    public void setRepeatedEvidenceRequestCount(int repeatedEvidenceRequestCount) {
        this.repeatedEvidenceRequestCount = repeatedEvidenceRequestCount;
    }

}
