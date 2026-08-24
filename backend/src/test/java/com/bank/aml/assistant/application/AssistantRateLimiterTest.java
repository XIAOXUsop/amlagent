package com.bank.aml.assistant.application;

import com.bank.aml.assistant.config.AssistantProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantRateLimiterTest {
    @Test
    void rejectsAboveConfiguredDistributedLimitAndRedisFailure() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AssistantProperties properties = new AssistantProperties();
        properties.setRateLimitPerMinute(2);
        AssistantRateLimiter limiter = new AssistantRateLimiter(redis, properties);
        when(redis.execute(any(RedisScript.class), any(java.util.List.class), any(Object[].class)))
                .thenReturn(3L);

        assertThatThrownBy(() -> limiter.check("admin")).isInstanceOf(AssistantRateLimitException.class);

        when(redis.execute(any(RedisScript.class), any(java.util.List.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis down"));
        assertThatThrownBy(() -> limiter.check("admin")).isInstanceOf(AssistantRateLimitException.class);
    }
}
