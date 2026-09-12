package com.bank.aml.assistant.config;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AssistantExecutorConfiguration {

    @Bean(name = "assistantTaskExecutor")
    public ThreadPoolTaskExecutor assistantTaskExecutor(AssistantProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getTaskCorePoolSize());
        executor.setMaxPoolSize(properties.getTaskMaxPoolSize());
        executor.setQueueCapacity(properties.getTaskQueueCapacity());
        executor.setThreadNamePrefix("assistant-run-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "assistantLeaseScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService assistantLeaseScheduler(AssistantProperties properties) {
        return Executors.newScheduledThreadPool(properties.getLeaseSchedulerThreads(), runnable -> {
            Thread thread = new Thread(runnable, "assistant-lease-renewal");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(name = "assistantSseExecutor")
    public ThreadPoolTaskExecutor assistantSseExecutor(AssistantProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getSseCorePoolSize());
        executor.setMaxPoolSize(properties.getSseMaxPoolSize());
        executor.setQueueCapacity(properties.getSseQueueCapacity());
        executor.setThreadNamePrefix("assistant-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

}
