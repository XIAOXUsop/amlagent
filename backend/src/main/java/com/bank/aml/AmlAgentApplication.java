package com.bank.aml;

import com.bank.aml.messaging.QueueProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 商业银行智能反洗钱（AML）与高风险客户尽调 Agent 启动入口。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(QueueProperties.class)
public class AmlAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmlAgentApplication.class, args);
    }
}
