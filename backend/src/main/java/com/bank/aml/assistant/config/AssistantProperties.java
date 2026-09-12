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

    private Context context = new Context();

    public Context getContext() { return context; }
    public void setContext(Context context) { this.context = context; }

    /**
     * 上下文治理配置，前缀 {@code aml.assistant.context}。
     *
     * <p>{@code enabled=false} 时**完全回退**到旧的"最近 N 条窗口"行为（{@code historyMaxMessages}），
     * 用于灰度与一键回滚——治理路径出问题时不必回退整个发布。
     */
    public static class Context {

        private boolean enabled = true;

        /** 模型上下文窗口 token 数，按实际 provider 设置 */
        @Min(1024)
        @Max(2_000_000)
        private int providerContextWindowTokens = 32_768;

        /** 输出预留：不参与治理，永远留给模型回答 */
        @Min(128)
        @Max(32_768)
        private int outputReserveTokens = 1_024;

        /** 工具往返预留：含历史取回工具的调用 */
        @Min(0)
        @Max(32_768)
        private int toolRoundReserveTokens = 2_048;

        /** token 估算的保守系数（%）：偏保守只会略早压缩，偏激进则可能超出真实窗口 */
        @Min(0)
        @Max(50)
        private int safetyMarginPercent = 15;

        /** 压缩回答时保留的头部字符数（结论所在） */
        @Min(0)
        @Max(2_000)
        private int answerHeadChars = 240;

        /** 压缩回答时保留的尾部字符数（数据局限与免责声明所在） */
        @Min(0)
        @Max(2_000)
        private int answerTailChars = 160;

        /** 单轮允许的历史取回调用次数上限 */
        @Min(0)
        @Max(20)
        private int maxRecallCallsPerRun = 3;

        @AssertTrue(message = "输出预留与工具预留之和必须小于上下文窗口")
        public boolean isReserveWithinWindow() {
            return outputReserveTokens + toolRoundReserveTokens < providerContextWindowTokens;
        }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getProviderContextWindowTokens() { return providerContextWindowTokens; }
        public void setProviderContextWindowTokens(int v) { this.providerContextWindowTokens = v; }
        public int getOutputReserveTokens() { return outputReserveTokens; }
        public void setOutputReserveTokens(int v) { this.outputReserveTokens = v; }
        public int getToolRoundReserveTokens() { return toolRoundReserveTokens; }
        public void setToolRoundReserveTokens(int v) { this.toolRoundReserveTokens = v; }
        public int getSafetyMarginPercent() { return safetyMarginPercent; }
        public void setSafetyMarginPercent(int v) { this.safetyMarginPercent = v; }
        public int getAnswerHeadChars() { return answerHeadChars; }
        public void setAnswerHeadChars(int v) { this.answerHeadChars = v; }
        public int getAnswerTailChars() { return answerTailChars; }
        public void setAnswerTailChars(int v) { this.answerTailChars = v; }
        public int getMaxRecallCallsPerRun() { return maxRecallCallsPerRun; }
        public void setMaxRecallCallsPerRun(int v) { this.maxRecallCallsPerRun = v; }
    }
}
