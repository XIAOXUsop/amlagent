package com.bank.aml.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class AssistantExecutorConfiguration {
    @Bean(name = "assistantTaskExecutor")
    public ThreadPoolTaskExecutor assistantTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("assistant-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "assistantLeaseScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService assistantLeaseScheduler() {
        return java.util.concurrent.Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "assistant-lease-renewal");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(name = "assistantSseExecutor")
    public ThreadPoolTaskExecutor assistantSseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(12);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("assistant-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
