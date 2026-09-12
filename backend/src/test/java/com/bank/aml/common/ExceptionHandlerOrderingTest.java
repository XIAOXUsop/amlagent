package com.bank.aml.common;

import com.bank.aml.assistant.api.AssistantExceptionHandler;
import com.bank.aml.sanction.SanctionExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.OrderUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** 确保领域 Advice 先于全局 Exception 兜底解析，避免稳定领域错误码被转换成 500。 */
class ExceptionHandlerOrderingTest {

    @Test
    void domainHandlersHaveHigherPriorityThanGlobalFallback() {
        assertThat(OrderUtils.getOrder(AssistantExceptionHandler.class, Ordered.LOWEST_PRECEDENCE))
            .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(OrderUtils.getOrder(SanctionExceptionHandler.class, Ordered.LOWEST_PRECEDENCE))
            .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(OrderUtils.getOrder(GlobalExceptionHandler.class, Ordered.HIGHEST_PRECEDENCE))
            .isEqualTo(Ordered.LOWEST_PRECEDENCE);

        assertThat(AssistantExceptionHandler.class.getAnnotation(Order.class)).isNotNull();
    }

}
