package com.bank.aml.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 共享心跳线程池：所有 Worker 消息处理复用同一调度器，避免每条消息各起一个单线程池。
 * <p>心跳任务为轻量数据库 UPDATE，池大小 2 足够；线程设为守护线程，随应用退出回收。
 */
@Configuration
public class HeartbeatExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService heartbeatExecutor() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "aml-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
}
