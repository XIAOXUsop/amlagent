package com.bank.aml.assistant.application;

import com.bank.aml.assistant.config.AssistantProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** 分布式固定窗口限流；Redis 不可用时失败关闭，不无界调用外部模型。 */
@Component
public class AssistantRateLimiter {
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
            "local n=redis.call('incr',KEYS[1]); if n==1 then redis.call('expire',KEYS[1],60) end; return n",
            Long.class);
    private final StringRedisTemplate redis;
    private final AssistantProperties properties;

    public AssistantRateLimiter(StringRedisTemplate redis, AssistantProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void check(String operatorUsername) {
        Long count;
        try {
            count = redis.execute(INCREMENT, List.of("aml:assistant:rate:" + operatorUsername));
        } catch (RuntimeException exception) {
            throw new AssistantRateLimitException();
        }
        if (count == null || count > properties.getRateLimitPerMinute()) {
            throw new AssistantRateLimitException();
        }
    }
}
