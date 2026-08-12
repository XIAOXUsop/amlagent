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
}
