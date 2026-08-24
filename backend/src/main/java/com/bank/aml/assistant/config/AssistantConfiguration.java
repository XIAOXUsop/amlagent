package com.bank.aml.assistant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册 AI 小助类型化配置；业务 Bean 在各工作包中按功能开关装配。 */
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfiguration {
}
