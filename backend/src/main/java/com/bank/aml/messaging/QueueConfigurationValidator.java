package com.bank.aml.messaging;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 防止健康 Worker 因配置错误被接管：租约空闲阈值至少覆盖两个心跳周期。 */
@Component
public class QueueConfigurationValidator implements ApplicationRunner {
    private final QueueProperties properties;

    public QueueConfigurationValidator(QueueProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.getHeartbeatSeconds() <= 0) {
            throw new IllegalStateException("aml.queue.heartbeat-seconds 必须大于 0");
        }
        if (properties.getClaimIdleSeconds() < properties.getHeartbeatSeconds() * 2) {
            throw new IllegalStateException("aml.queue.claim-idle-seconds 必须至少为 heartbeat-seconds 的两倍");
        }
    }
}
