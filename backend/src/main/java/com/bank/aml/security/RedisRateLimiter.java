package com.bank.aml.security;

import com.bank.aml.common.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 通用端点限流（Redis 实现，多实例共享）：按维度 Key 做固定窗口计数。
 * 用于调试、评测等管理类昂贵端点；Redis 故障时 fail-open 并告警。
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String PREFIX = "aml:rate:";

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 超过窗口内上限时抛出 {@link TooManyRequestsException}。
     *
     * @param bucket        维度键（调用方负责拼接主体标识，如操作者用户名）
     * @param limit         窗口内允许次数
     * @param windowSeconds 窗口秒数
     */
    public void checkLimit(String bucket, int limit, int windowSeconds) {
        String key = PREFIX + bucket;
        try {
            Long count = redis.opsForValue().increment(key);
            // 每次都刷新 TTL（幂等）：避免首次 expire 失败留下永不过期的计数键造成永久 429
            redis.expire(key, Duration.ofSeconds(Math.max(1, windowSeconds)));
            if (count != null && count > limit) {
                throw new TooManyRequestsException("请求过于频繁，请稍后再试");
            }
        } catch (TooManyRequestsException limited) {
            throw limited;
        } catch (RuntimeException e) {
            log.warn("端点限流 Redis 不可用，降级放行: {}", e.getMessage());
        }
    }
}