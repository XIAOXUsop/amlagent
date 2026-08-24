package com.bank.aml.assistant.guard;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** 默认测试阶段执行的轻量 Spring 装配测试，不依赖 Docker。 */
class AssistantOutputGuardContextTest {

    @Test
    void contextLoads() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(SensitiveDataDetector.class, ClaimCitationValidator.class, AssistantOutputGuard.class);
            context.refresh();

            assertThat(context.getBean(AssistantOutputGuard.class)).isNotNull();
        }
    }
}
