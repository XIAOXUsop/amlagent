package com.bank.aml.messaging;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Stream 消费进度跟踪：记录消费者已成功 ACK 的消息数。
 * <p>用于健康监控判定"消费者是否在推进"。停摆的典型表现是：stream 中仍有消息，
 * 但已 ACK 计数不再增长（消费者/数据库连接中断或 handler 卡死）。
 */
@Component
public class StreamConsumptionTracker {

    private final AtomicLong ackTotal = new AtomicLong(0);

    public void recordAck() {
        ackTotal.incrementAndGet();
    }

    public long ackTotal() {
        return ackTotal.get();
    }
}
