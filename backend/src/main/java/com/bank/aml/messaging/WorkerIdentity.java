package com.bank.aml.messaging;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Worker 实例标识：每次启动生成随机后缀，避免多实例部署时消费者标识冲突。
 */
@Component
public class WorkerIdentity {

    private final String consumerName = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public String consumerName() {
        return consumerName;
    }
}
