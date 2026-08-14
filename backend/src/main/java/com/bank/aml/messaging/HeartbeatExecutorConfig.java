package com.bank.aml.messaging;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 共享线程池配置：心跳调度器 + 流式摘要执行器。
 * <p>均使用有界线程池，避免公共 ForkJoinPool；线程设为守护线程，随应用退出回收。
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

    /** 流式摘要执行器：有界线程池，避免摘要任务占用 Worker/公共 ForkJoinPool */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService summaryExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "aml-summary");
            t.setDaemon(true);
            return t;
        });
    }
}
