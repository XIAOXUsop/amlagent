package com.bank.aml.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 可靠审计投递的批处理与调度参数。 */
@ConfigurationProperties(prefix = "aml.audit")
@Validated
public class AuditProperties {

    @Min(100)
    @Max(60_000)
    private long outboxPollMs = 2_000;

    @Min(1)
    @Max(2_000)
    private int outboxBatchSize = 200;

    public long getOutboxPollMs() {
        return outboxPollMs;
    }

    public void setOutboxPollMs(long outboxPollMs) {
        this.outboxPollMs = outboxPollMs;
    }

    public int getOutboxBatchSize() {
        return outboxBatchSize;
    }

    public void setOutboxBatchSize(int outboxBatchSize) {
        this.outboxBatchSize = outboxBatchSize;
    }

}
