package com.bank.aml.assistant.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class AssistantRunExecutor {
    private final ThreadPoolTaskExecutor executor;

    public AssistantRunExecutor(@Qualifier("assistantTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    public boolean submit(Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (TaskRejectedException exception) {
            return false;
        }
    }
}
