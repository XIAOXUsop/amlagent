package com.bank.aml.assistant.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** AI 小助的容量、安全和生命周期配置。 */
@Validated
@ConfigurationProperties(prefix = "aml.assistant")
public class AssistantProperties {

    private boolean enabled;

    @Min(100)
    @Max(10_000)
    private int maxMessageChars = 2_000;

    @Min(1)
    @Max(365)
    private int retentionDays = 7;

    @Min(1)
    @Max(600)
    private int rateLimitPerMinute = 10;

    @Min(5)
    @Max(600)
    private int runTimeoutSeconds = 120;

    @Min(1)
    @Max(1440)
    private int eventStreamTtlMinutes = 10;

    @Min(15)
    @Max(600)
    private int leaseTtlSeconds = 120;

    @Min(5)
    @Max(300)
    private int leaseRenewSeconds = 30;

    @Min(2)
    @Max(50)
    private int historyMaxMessages = 12;

    @Min(1)
    @Max(10)
    private int maxToolRoundTrips = 5;

    @Min(8)
    @Max(256)
    private int validatedStreamChunkChars = 48;

    @Min(0)
    @Max(100)
    private int validatedStreamChunkDelayMs = 12;

    @AssertTrue(message = "lease-ttl-seconds 必须至少是 lease-renew-seconds 的两倍")
    public boolean isLeaseWindowValid() {
        return leaseTtlSeconds >= leaseRenewSeconds * 2;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxMessageChars() { return maxMessageChars; }
    public void setMaxMessageChars(int maxMessageChars) { this.maxMessageChars = maxMessageChars; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public int getRunTimeoutSeconds() { return runTimeoutSeconds; }
    public void setRunTimeoutSeconds(int runTimeoutSeconds) { this.runTimeoutSeconds = runTimeoutSeconds; }
    public int getEventStreamTtlMinutes() { return eventStreamTtlMinutes; }
    public void setEventStreamTtlMinutes(int eventStreamTtlMinutes) { this.eventStreamTtlMinutes = eventStreamTtlMinutes; }
    public int getLeaseTtlSeconds() { return leaseTtlSeconds; }
    public void setLeaseTtlSeconds(int leaseTtlSeconds) { this.leaseTtlSeconds = leaseTtlSeconds; }
    public int getLeaseRenewSeconds() { return leaseRenewSeconds; }
    public void setLeaseRenewSeconds(int leaseRenewSeconds) { this.leaseRenewSeconds = leaseRenewSeconds; }
    public int getHistoryMaxMessages() { return historyMaxMessages; }
    public void setHistoryMaxMessages(int historyMaxMessages) { this.historyMaxMessages = historyMaxMessages; }
    public int getMaxToolRoundTrips() { return maxToolRoundTrips; }
    public void setMaxToolRoundTrips(int maxToolRoundTrips) { this.maxToolRoundTrips = maxToolRoundTrips; }
    public int getValidatedStreamChunkChars() { return validatedStreamChunkChars; }
    public void setValidatedStreamChunkChars(int validatedStreamChunkChars) { this.validatedStreamChunkChars = validatedStreamChunkChars; }
    public int getValidatedStreamChunkDelayMs() { return validatedStreamChunkDelayMs; }
    public void setValidatedStreamChunkDelayMs(int validatedStreamChunkDelayMs) { this.validatedStreamChunkDelayMs = validatedStreamChunkDelayMs; }
}
