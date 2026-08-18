package com.bank.aml.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可靠任务队列配置（Redis Streams）。
 */
@ConfigurationProperties(prefix = "aml.queue")
public class QueueProperties {

    /** 任务 Stream */
    private String stream = "aml:workflow:cases";

    /** 死信 Stream */
    private String deadStream = "aml:workflow:dead";

    /** 消费者组 */
    private String group = "aml-workers";

    /** 当前 Worker 标识 */
    private String consumer = "worker-1";

    /** 单工单最大重试次数（超过进死信） */
    private int maxRetry = 3;

    /** Pending 消息接管阈值（秒） */
    private long claimIdleSeconds = 60;

    /** Outbox 扫描间隔（秒） */
    private int outboxPollSeconds = 5;

    /** 重试指数退避基数（秒）：delay = base * 2^retry */
    private int retryBackoffSeconds = 10;

    // ---- 连接恢复与健康监控 ----
    /** 消费者健康探测间隔（秒） */
    private int healthProbeSeconds = 15;

    /** lag 告警阈值（未消费消息数超过即告警，0 = 禁用） */
    private long lagAlertThreshold = 20;

    /** lag 持续达到该阈值后，尝试重建消费者容器自动恢复（应大于 lagAlertThreshold） */
    private long lagRecoverThreshold = 50;

    /** 连续探测到异常达到该次数后触发告警（避免单一瞬断误报）；0 = 首次即告警 */
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

    public String getConsumer() {
        return consumer;
    }

    public void setConsumer(String consumer) {
        this.consumer = consumer;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public long getClaimIdleSeconds() {
        return claimIdleSeconds;
    }

    public void setClaimIdleSeconds(long claimIdleSeconds) {
        this.claimIdleSeconds = claimIdleSeconds;
    }

    public int getOutboxPollSeconds() {
        return outboxPollSeconds;
    }

    public void setOutboxPollSeconds(int outboxPollSeconds) {
        this.outboxPollSeconds = outboxPollSeconds;
    }

    public int getRetryBackoffSeconds() {
        return retryBackoffSeconds;
    }

    public void setRetryBackoffSeconds(int retryBackoffSeconds) {
        this.retryBackoffSeconds = retryBackoffSeconds;
    }

    public int getHealthProbeSeconds() {
        return healthProbeSeconds;
    }

    public void setHealthProbeSeconds(int healthProbeSeconds) {
        this.healthProbeSeconds = healthProbeSeconds;
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
