package com.bank.aml.messaging;

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

/**
 * Redis Streams 消费者容器：消费尽调任务，应用重启后 Pending 消息可被重新投递接管。
 */
@Component
public class WorkflowConsumer implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WorkflowConsumer.class);

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final QueueProperties props;
    private final WorkflowMessageHandler handler;
    private final WorkerIdentity workerIdentity;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    public WorkflowConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                            QueueProperties props, WorkflowMessageHandler handler, WorkerIdentity workerIdentity) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.props = props;
        this.handler = handler;
        this.workerIdentity = workerIdentity;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 创建消费者组（已存在则忽略）
        try {
            redisTemplate.opsForStream().createGroup(props.getStream(), ReadOffset.latest(), props.getGroup());
        } catch (Exception e) {
            log.info("消费者组已存在或创建失败（忽略）：{}", e.getMessage());
        }

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .<String, MapRecord<String, String, String>>builder()
                        .pollTimeout(Duration.ofMillis(300))
                        .serializer(RedisSerializer.string())
                        .errorHandler(t -> log.error("Stream 监听异常", t))
                        .build();

        container = StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(
                Consumer.from(props.getGroup(), workerIdentity.consumerName()),
                StreamOffset.create(props.getStream(), ReadOffset.lastConsumed()),
                handler::onMessage);
        container.start();
        log.info("Redis Streams 消费者已启动 stream={} group={} consumer={}",
                props.getStream(), props.getGroup(), workerIdentity.consumerName());
    }

    @Override
    public void destroy() {
        if (container != null) {
            container.stop();
        }
    }
}
