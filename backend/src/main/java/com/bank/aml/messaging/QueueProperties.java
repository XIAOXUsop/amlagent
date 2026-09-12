package com.bank.aml.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 可靠任务队列配置（Redis Streams）。
 */
@ConfigurationProperties(prefix = "aml.queue")
@Validated
public class QueueProperties {

    /** 任务 Stream */
    @NotBlank
    private String stream = "aml:workflow:cases";

    /** 死信 Stream */
    @NotBlank
    private String deadStream = "aml:workflow:dead";

    /** 消费者组 */
    @NotBlank
    private String group = "aml-workers";

    /** 单工单最大重试次数（超过进死信） */
    @Min(0)
    @Max(20)
    private int maxRetry = 3;

    /**
     * @deprecated 工作流 Stream 禁止按 MAXLEN 裁剪；保留字段仅兼容旧配置。
     */
    @Deprecated
    private long streamMaxLen = 10000;

    /** Pending 消息接管阈值（秒） */
    @Min(1)
    private long claimIdleSeconds = 60;

    /** Worker 心跳间隔；claimIdleSeconds 必须至少为其两倍。 */
    @Min(1)
    private long heartbeatSeconds = 30;

    /** Outbox 扫描间隔（秒） */
    @Min(1)
    private int outboxPollSeconds = 5;

    /** 发布 Claim 可被其他实例接管前的陈旧窗口（秒）。 */
    @Min(1)
    @Max(3_600)
    private long outboxClaimStaleSeconds = 30;

    /** 单轮发布最大事件数。 */
    @Min(1)
    @Max(10_000)
    private int outboxPublishBatchSize = 200;

    /** 消费者阻塞轮询超时（毫秒）。 */
    @Min(10)
    @Max(60_000)
    private long consumerPollTimeoutMs = 300;

    /** 重试指数退避基数（秒）：delay = base * 2^retry */
    @Min(1)
    private int retryBackoffSeconds = 5;

    /** 指数退避指数上限，避免移位溢出与无限等待。 */
    @Min(0)
    @Max(30)
    private int retryBackoffExponentCap = 6;

    /** 到期重试扫描周期（秒）。 */
    @Min(1)
    @Max(3_600)
    private int retryPollSeconds = 5;

    // ---- 连接恢复与健康监控 ----
    /** 消费者健康探测间隔（秒） */
    @Min(1)
    private int healthProbeSeconds = 15;

    /** 应用启动后首次健康探测的等待时间（秒）。 */
    @Min(0)
    @Max(3_600)
    private int healthInitialDelaySeconds = 5;

    /** 达到恢复 lag 阈值后，触发重建前至少连续异常的周期数。 */
    @Min(1)
    @Max(100)
    private int minimumLagRecoveryCycles = 2;

    /** Worker 租约心跳调度线程数。 */
    @Min(1)
    @Max(64)
    private int heartbeatExecutorThreads = 2;

    /** 最终报告推送执行线程数。 */
    @Min(1)
    @Max(64)
    private int summaryExecutorThreads = 2;

    /** lag 告警阈值（未消费消息数超过即告警，0 = 禁用） */
    @Min(0)
    private long lagAlertThreshold = 20;

    /** lag 持续达到该阈值后，尝试重建消费者容器自动恢复（应大于 lagAlertThreshold） */
    @Min(0)
    private long lagRecoverThreshold = 50;

    /** 连续探测到异常达到该次数后触发告警（避免单一瞬断误报）；0 = 首次即告警 */
    @Min(0)
    private int consecutiveErrorAlertThreshold = 3;

    public String getStream() {
        return stream;
    }

    public void setStream(String stream) {
        this.stream = stream;
    }

    public String getDeadStream() {
        return deadStream;
    }

    public void setDeadStream(String deadStream) {
        this.deadStream = deadStream;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public long getStreamMaxLen() {
        return streamMaxLen;
    }

    public void setStreamMaxLen(long streamMaxLen) {
        this.streamMaxLen = streamMaxLen;
    }

    public long getClaimIdleSeconds() {
        return claimIdleSeconds;
    }

    public void setClaimIdleSeconds(long claimIdleSeconds) {
        this.claimIdleSeconds = claimIdleSeconds;
    }

    public long getHeartbeatSeconds() {
        return heartbeatSeconds;
    }

    public void setHeartbeatSeconds(long heartbeatSeconds) {
        this.heartbeatSeconds = heartbeatSeconds;
    }

    public int getOutboxPollSeconds() {
        return outboxPollSeconds;
    }

    public void setOutboxPollSeconds(int outboxPollSeconds) {
        this.outboxPollSeconds = outboxPollSeconds;
    }

    public long getOutboxClaimStaleSeconds() {
        return outboxClaimStaleSeconds;
    }

    public void setOutboxClaimStaleSeconds(long outboxClaimStaleSeconds) {
        this.outboxClaimStaleSeconds = outboxClaimStaleSeconds;
    }

    public int getOutboxPublishBatchSize() {
        return outboxPublishBatchSize;
    }

    public void setOutboxPublishBatchSize(int outboxPublishBatchSize) {
        this.outboxPublishBatchSize = outboxPublishBatchSize;
    }

    public long getConsumerPollTimeoutMs() {
        return consumerPollTimeoutMs;
    }

    public void setConsumerPollTimeoutMs(long consumerPollTimeoutMs) {
        this.consumerPollTimeoutMs = consumerPollTimeoutMs;
    }

    public int getRetryBackoffSeconds() {
        return retryBackoffSeconds;
    }

    public void setRetryBackoffSeconds(int retryBackoffSeconds) {
        this.retryBackoffSeconds = retryBackoffSeconds;
    }

    public int getRetryBackoffExponentCap() {
        return retryBackoffExponentCap;
    }

    public void setRetryBackoffExponentCap(int retryBackoffExponentCap) {
        this.retryBackoffExponentCap = retryBackoffExponentCap;
    }

    public int getRetryPollSeconds() {
        return retryPollSeconds;
    }

    public void setRetryPollSeconds(int retryPollSeconds) {
        this.retryPollSeconds = retryPollSeconds;
    }

    public int getHealthProbeSeconds() {
        return healthProbeSeconds;
    }

    public void setHealthProbeSeconds(int healthProbeSeconds) {
        this.healthProbeSeconds = healthProbeSeconds;
    }

    public int getHealthInitialDelaySeconds() {
        return healthInitialDelaySeconds;
    }

    public void setHealthInitialDelaySeconds(int healthInitialDelaySeconds) {
        this.healthInitialDelaySeconds = healthInitialDelaySeconds;
    }

    public int getMinimumLagRecoveryCycles() {
        return minimumLagRecoveryCycles;
    }

    public void setMinimumLagRecoveryCycles(int minimumLagRecoveryCycles) {
        this.minimumLagRecoveryCycles = minimumLagRecoveryCycles;
    }

    public int getHeartbeatExecutorThreads() {
        return heartbeatExecutorThreads;
    }

    public void setHeartbeatExecutorThreads(int heartbeatExecutorThreads) {
        this.heartbeatExecutorThreads = heartbeatExecutorThreads;
    }

    public int getSummaryExecutorThreads() {
        return summaryExecutorThreads;
    }

    public void setSummaryExecutorThreads(int summaryExecutorThreads) {
        this.summaryExecutorThreads = summaryExecutorThreads;
    }

    public long getLagAlertThreshold() {
        return lagAlertThreshold;
    }

    public void setLagAlertThreshold(long lagAlertThreshold) {
        this.lagAlertThreshold = lagAlertThreshold;
    }

    public long getLagRecoverThreshold() {
        return lagRecoverThreshold;
    }

    public void setLagRecoverThreshold(long lagRecoverThreshold) {
        this.lagRecoverThreshold = lagRecoverThreshold;
    }

    public int getConsecutiveErrorAlertThreshold() {
        return consecutiveErrorAlertThreshold;
    }

    public void setConsecutiveErrorAlertThreshold(int consecutiveErrorAlertThreshold) {
        this.consecutiveErrorAlertThreshold = consecutiveErrorAlertThreshold;
    }

}
