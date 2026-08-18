package com.bank.aml.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Redis Streams 消费者健康监控与自动恢复调度。
 * <p>周期驱动 {@link WorkflowConsumer#probeLag()}，把"瞬时尖峰"与"持续停摆"区分开，
 * 对检测到的消费者停滞 / 连接中断做分级响应：
 * <ol>
 *   <li><b>连接中断</b>：探测失败一次即触发消费者容器重建（自助恢复）；</li>
 *   <li><b>持续积压</b>：lag 连续超过告警阈值且持续多个周期后记录告警日志；
 *       若继续走高超过恢复阈值，则强制重建消费者容器以恢复消费。</li>
 * </ol>
 * <p>阈值来自 {@code aml.queue} 配置，见 {@link QueueProperties}。
 */
@Component
public class StreamHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(StreamHealthMonitor.class);

    private final WorkflowConsumer consumer;
    private final QueueProperties props;

    /** 持续检测到"非健康"状态的周期计数 */
    private int unhealthyCycles = 0;
    /** 是否已处于告警态（避免每周期重复告警刷屏） */
    private boolean alerted = false;

    public StreamHealthMonitor(WorkflowConsumer consumer, QueueProperties props) {
        this.consumer = consumer;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${aml.queue.health-probe-seconds:10}000", initialDelay = 5000)
    public void monitor() {
        boolean healthy = consumer.probeLag();
        long lagAlert = props.getLagAlertThreshold();
        long lagRecover = props.getLagRecoverThreshold();

        if (healthy) {
            unhealthyCycles = 0;
            if (alerted) {
                log.info("Redis Streams 消费者已恢复健康（stream={}）", props.getStream());
                alerted = false;
            }
            return;
        }

        // 连接中断：优先尝试立即恢复（probeLag 已重建过一次，这里再兜底一次）
        if (consumer.lastProbeFailed()) {
            if (consumer.restartIfNeeded(false)) {
                log.warn("检测到 Redis 连接异常，已重建消费者容器以恢复消费");
            }
            return;
        }

        // 积压停滞：累积周期，过滤瞬时尖峰
        unhealthyCycles++;
        long lag = consumer.lastLag();

        // 持续达到恢复阈值：强制重建（自助恢复）
        if (lagRecover > 0 && unhealthyCycles >= Math.max(2, props.getConsecutiveErrorAlertThreshold())
                && lag >= lagRecover) {
            log.error("ALERT Redis Streams 消费者停滞，lag={} 持续 {} 个周期超过恢复阈值 {}，强制重建容器",
                    lag, unhealthyCycles, lagRecover);
            consumer.restartIfNeeded(true);
            unhealthyCycles = 0;
            alerted = false;
            return;
        }

        // 首次持续达到告警阈值：记录告警，不立即重建
        if (lagAlert > 0 && !alerted && unhealthyCycles >= Math.max(1, props.getConsecutiveErrorAlertThreshold())
                && lag >= lagAlert) {
            log.error("ALERT Redis Streams 消费者疑似停滞，消息积压 lag={} 持续 {} 个周期（告警阈值 {}，恢复阈值 {}）",
                    lag, unhealthyCycles, lagAlert, lagRecover);
            alerted = true;
        }
    }
}
