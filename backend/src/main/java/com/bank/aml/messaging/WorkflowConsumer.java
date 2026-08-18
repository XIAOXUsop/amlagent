package com.bank.aml.messaging;

import com.bank.aml.observability.MetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis Streams 消费者容器：消费尽调任务，应用重启后 Pending 消息可被重新投递接管。
 * <p>连接加固：消费者容器在 Redis 短暂中断（如 Docker 重启）后可能停摆不再消费，
 * 本实现配合 {@link StreamHealthMonitor} 实现自愈：
 * <ul>
 *   <li>暴露 {@link #probeLag()} 供健康监控读取消费 lag 与容器状态；</li>
 *   <li>暴露 {@link #restartIfNeeded(boolean)} 供监控在检测到停滞/连接异常时重建容器；</li>
 *   <li>容器内部错误回调 {@link #onContainerError()} 记录告警指标与日志。</li>
 * </ul>
 */
@Component
public class WorkflowConsumer implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WorkflowConsumer.class);

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final QueueProperties props;
    private final WorkflowMessageHandler handler;
    private final WorkerIdentity workerIdentity;
    private final MetricsRecorder metrics;
    private final StreamConsumptionTracker consumptionTracker;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 最近一次探测到消费滞后的实际消息数 */
    private final AtomicLong lastLag = new AtomicLong(0);
    /** 最近一次探测是否因连接失败而未成功 */
    private final AtomicBoolean lastProbeFailed = new AtomicBoolean(false);
    /** 上次探测时的已 ACK 计数，用于判断消费者是否在推进 */
    private long lastAckTotal = 0;

    public WorkflowConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                            QueueProperties props, WorkflowMessageHandler handler, WorkerIdentity workerIdentity,
                            MetricsRecorder metrics, StreamConsumptionTracker consumptionTracker) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.props = props;
        this.handler = handler;
        this.workerIdentity = workerIdentity;
        this.metrics = metrics;
        this.consumptionTracker = consumptionTracker;
    }

    @Override
    public void run(ApplicationArguments args) {
        startContainer(true);
    }

    /** 启动消费者容器；firstStart=true 时创建消费者组（已存在则忽略）。幂等：已在运行时直接返回。 */
    private synchronized void startContainer(boolean firstStart) {
        if (running.get()) {
            return;
        }
        try {
            if (firstStart) {
                try {
                    redisTemplate.opsForStream().createGroup(props.getStream(), ReadOffset.latest(), props.getGroup());
                } catch (Exception e) {
                    log.info("消费者组已存在或创建失败（忽略）：{}", e.getMessage());
                }
            }
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                    StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                            .<String, MapRecord<String, String, String>>builder()
                            .pollTimeout(Duration.ofMillis(300))
                            .serializer(RedisSerializer.string())
                            .errorHandler(t -> onContainerError())
                            .build();

            container = StreamMessageListenerContainer.create(connectionFactory, options);
            container.receive(
                    Consumer.from(props.getGroup(), workerIdentity.consumerName()),
                    StreamOffset.create(props.getStream(), ReadOffset.lastConsumed()),
                    handler::onMessage);
            container.start();
            running.set(true);
            lastProbeFailed.set(false);
            log.info("Redis Streams 消费者已启动 stream={} group={} consumer={}",
                    props.getStream(), props.getGroup(), workerIdentity.consumerName());
        } catch (Exception e) {
            running.set(false);
            log.error("Redis Streams 消费者启动失败，将在健康探测中重试：{}", e.toString());
            onContainerError();
        }
    }

    /** 停止消费者容器（幂等）。 */
    private synchronized void stopContainer() {
        if (container != null) {
            try {
                container.stop();
            } catch (Exception e) {
                log.debug("停止消费者容器忽略异常：{}", e.toString());
            }
            container = null;
        }
        running.set(false);
    }

    /** 容器内部错误回调：记录告警指标与日志。 */
    private void onContainerError() {
        metrics.queueConsumerError();
        log.error("Redis Streams 监听异常：消费者容器可能停摆（stream={}），将由健康监控检查并尝试恢复",
                props.getStream());
    }

    /**
     * 健康探测：评估消费者是否在正常推进。供 {@link StreamHealthMonitor} 周期调度调用。
     * <p>判据：正常消费时，消费者持续读取并 ACK 消息。若 stream 中仍有未消费消息，
     * 但已 ACK 计数不增长，说明消费者停摆（连接中断或 handler 卡死）。
     * lag 估算为仍未 ACK 的 pending 数；pending 无法反映"完全未读取"部分时，
     * 用"stream 非空且 ACK 不推进"作为停滞判据。
     * @return true 表示健康；false 表示停滞（有积压且消费者未推进）或连接异常。
     */
    public boolean probeLag() {
        if (!running.get()) {
            log.warn("Redis Streams 消费者未运行，尝试重建容器（stream={}）", props.getStream());
            metrics.queueConsumerDown();
            startContainer(false);
            lastProbeFailed.set(true);
            return false;
        }
        try {
            Long streamLen = redisTemplate.opsForStream().size(props.getStream());
            long ackTotal = consumptionTracker.ackTotal();
            // 本周期消费者是否推进（ACK 相比上次探测增加，说明在读消息）
            boolean progressing = ackTotal > lastAckTotal;
            lastAckTotal = ackTotal;

            long lag;
            if (streamLen == null || streamLen <= 0) {
                // stream 为空：无积压，健康（即使 ack 未推进也正常）
                lag = 0;
            } else {
                // stream 非空：积压以 pending（已投递未 ACK）计；pending 取不到时退化为 stream 长度
                long pending = pendingCount();
                lag = pending >= 0 ? pending : streamLen;
                // 停滞判据：有积压但消费者未推进（未读取/未 ACK）
                if (lag <= 0 && !progressing) {
                    // pending 为 0 但 stream 非空，说明消息"完全未被读取"（消费组未投递），记积压为 stream 长度
                    lag = streamLen;
                }
            }

            lastLag.set(Math.max(0, lag));
            lastProbeFailed.set(false);
            metrics.queueLag(lastLag.get());

            boolean stalled;
            if (lastLag.get() <= 0) {
                stalled = false;
            } else {
                // 有积压；若消费者未推进则停滞，若在推进则是追赶中（健康）
                stalled = !progressing;
            }
            if (lastLag.get() > 0 && progressing) {
                log.debug("Redis Stream 消费追赶中，剩余 lag={}", lastLag.get());
            }
            return !stalled;
        } catch (Exception e) {
            lastProbeFailed.set(true);
            metrics.queueConsumerError();
            log.warn("Redis 健康探测失败（可能是连接中断）：{}", e.toString());
            return false;
        }
    }

    /** 读取消费组 pending（已投递未 ACK）数量；取不到返回 -1。 */
    private long pendingCount() {
        return redisTemplate.opsForStream().groups(props.getStream()).stream()
                .filter(g -> props.getGroup().equals(g.groupName()))
                .mapToLong(g -> g.pendingCount() == null ? 0L : g.pendingCount())
                .findFirst()
                .orElse(-1L);
    }

    /**
     * 按需重建消费者容器。供监控在检测到停滞或连接异常时调用。
     * @param forced true 表示无条件重建（用于滞后超阈值）；false 表示仅当连接异常时重建
     * @return true 表示已停止并重建；false 表示本次无需重建
     */
    public synchronized boolean restartIfNeeded(boolean forced) {
        boolean broken = lastProbeFailed.get();
        if (forced || broken || !running.get()) {
            log.info("重建 Redis Streams 消费者容器（forced={} probeFailed={} running={}）",
                    forced, broken, running.get());
            stopContainer();
            startContainer(false);
            return true;
        }
        return false;
    }

    /** 最近一次探测的 lag（供指标/断言） */
    public long lastLag() {
        return lastLag.get();
    }

    /** 最近一次探测是否连接失败 */
    public boolean lastProbeFailed() {
        return lastProbeFailed.get();
    }

    /** 容器当前是否运行 */
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void destroy() {
        stopContainer();
    }
}
