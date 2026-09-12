package com.bank.aml.security;

import com.bank.aml.common.exception.TooManyRequestsException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录失败速率限制（Redis 实现，多实例共享）：按"客户端 IP + 用户名"维度计数。
 * <ul>
 * <li>窗口内失败达到 {@code maxAttempts} 后写入锁定 Key（TTL = lockSeconds）；</li>
 * <li>失败计数 Key 每次都刷新 TTL，语义为"自最后一次失败起窗口内累计"的滑动式防护；</li>
 * <li>Redis 故障时 fail-open 并告警：可用性优先，暴力破解防护降级（登录审计仍然记录失败）。</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(LoginRateLimitProperties.class)
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final String FAIL_PREFIX = "aml:login:fail:";

    private static final String LOCK_PREFIX = "aml:login:lock:";

    private final LoginRateLimitProperties props;

    private final StringRedisTemplate redis;

    public LoginRateLimiter(LoginRateLimitProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    /** 请求前检查是否已被锁定；命中锁定直接拒绝。 */
    public void checkBlocked(String ip, String username) {
        if (props.getMaxAttempts() <= 0) {
            return;
        }
        String lockKey = key(LOCK_PREFIX, ip, username);
        try {
            if (Boolean.TRUE.equals(redis.hasKey(lockKey))) {
                throw new TooManyRequestsException("登录尝试过于频繁，请稍后再试");
            }
        }
        catch (TooManyRequestsException blocked) {
            throw blocked;
        }
        catch (RuntimeException e) {
            log.warn("登录限流 Redis 不可用，降级放行（防护降级）: {}", e.getMessage());
        }
    }

    /** 登录失败后记录：达到阈值则进入锁定状态。 */
    public void recordFailure(String ip, String username) {
        if (props.getMaxAttempts() <= 0) {
            return;
        }
        String failKey = key(FAIL_PREFIX, ip, username);
        String lockKey = key(LOCK_PREFIX, ip, username);
        try {
            Long count = redis.opsForValue().increment(failKey);
            // 每次失败都刷新 TTL：窗口随最后一次失败滑动，攻击者无法用时间摊薄计数
            redis.expire(failKey, Duration.ofSeconds(Math.max(1, props.getWindowSeconds())));
            if (count != null && count >= props.getMaxAttempts()) {
                redis.opsForValue().set(lockKey, "1", Duration.ofSeconds(Math.max(1, props.getLockSeconds())));
                redis.delete(failKey);
            }
        }
        catch (RuntimeException e) {
            log.warn("登录失败计数写入 Redis 失败（防护降级）: {}", e.getMessage());
        }
    }

    /** 登录成功后清除失败计数与锁定，避免误伤后续正常使用。 */
    public void reset(String ip, String username) {
        try {
            redis.delete(key(FAIL_PREFIX, ip, username));
            redis.delete(key(LOCK_PREFIX, ip, username));
        }
        catch (RuntimeException e) {
            log.warn("登录限流重置失败（下次登录会重新计数）: {}", e.getMessage());
        }
    }

    private String key(String prefix, String ip, String username) {
        return prefix + ip + "|" + (username == null ? "" : username.trim().toLowerCase());
    }

}
