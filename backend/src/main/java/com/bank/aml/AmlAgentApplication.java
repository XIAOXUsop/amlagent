package com.bank.aml;

import com.bank.aml.config.AgentOutputProperties;
import com.bank.aml.config.AmlProperties;
import com.bank.aml.config.AuditProperties;
import com.bank.aml.config.DatabaseDeploymentProperties;
import com.bank.aml.config.ExplanationProperties;
import com.bank.aml.config.OperationsProperties;
import com.bank.aml.config.RiskProperties;
import com.bank.aml.config.WorkflowProperties;
import com.bank.aml.messaging.QueueProperties;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 商业银行智能反洗钱（AML）与高风险客户尽调 Agent 启动入口。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({ QueueProperties.class, AmlProperties.class, AgentOutputProperties.class,
        AuditProperties.class, DatabaseDeploymentProperties.class, ExplanationProperties.class,
        OperationsProperties.class, RiskProperties.class, WorkflowProperties.class })
public class AmlAgentApplication {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(AmlAgentApplication.class, args);
    }

}
