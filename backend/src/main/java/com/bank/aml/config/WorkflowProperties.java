package com.bank.aml.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 工作流实时事件通道的运行参数。 */
@ConfigurationProperties(prefix = "aml.workflow")
@Validated
public class WorkflowProperties {

    @Min(1)
    @Max(300)
    private long eventHeartbeatSeconds = 15;

    @Min(1)
    @Max(32)
    private int eventHeartbeatThreads = 2;

    public long getEventHeartbeatSeconds() {
        return eventHeartbeatSeconds;
    }

    public void setEventHeartbeatSeconds(long eventHeartbeatSeconds) {
        this.eventHeartbeatSeconds = eventHeartbeatSeconds;
    }

    public int getEventHeartbeatThreads() {
        return eventHeartbeatThreads;
    }

    public void setEventHeartbeatThreads(int eventHeartbeatThreads) {
        this.eventHeartbeatThreads = eventHeartbeatThreads;
    }

}
