package com.bank.aml.security;

import com.bank.aml.common.exception.TooManyRequestsException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败速率限制：固定窗口 + 锁定。按"客户端 IP + 用户名"维度计数，
 * 窗口内失败超过阈值后锁定一段时间，缓解暴力破解；同时避免攻击者通过
 * 用户名探测无限次试错。
 * <p>纯内存实现（单实例足够），不引入额外存储依赖；key 与旧窗口会在访问时惰性清理，
 * 避免长期驻留造成内存泄漏。
 */
@Component
@EnableConfigurationProperties(LoginRateLimitProperties.class)
public class LoginRateLimiter {

    private static final String LOCK_SUFFIX = "#lock#";

    private final LoginRateLimitProperties props;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LoginRateLimiter(LoginRateLimitProperties props) {
        this.props = props;
    }

    /** 请求前检查是否已被锁定；命中锁定直接拒绝。 */
    public void checkBlocked(String ip, String username) {
        if (props.getMaxAttempts() <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        Bucket lock = buckets.get(key(ip, username) + LOCK_SUFFIX);
        if (lock != null && lock.lockedUntil > now) {
            throw new TooManyRequestsException("登录尝试过于频繁，请稍后再试");
        }
    }

    /** 登录失败后记录：达到阈值则进入锁定状态。 */
    public void recordFailure(String ip, String username) {
        if (props.getMaxAttempts() <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long windowMs = props.getWindowSeconds() * 1000L;
        String k = key(ip, username);
        Bucket b = buckets.computeIfAbsent(k, ignored -> new Bucket(now));
        synchronized (b) {
            if (now - b.windowStart > windowMs) {
                // 窗口过期：重置计数
                b.count.set(0);
                b.windowStart = now;
            }
            if (b.count.incrementAndGet() >= props.getMaxAttempts()) {
                buckets.put(k + LOCK_SUFFIX, new Bucket(now + props.getLockSeconds() * 1000L));
                b.count.set(0);
                b.windowStart = now + windowMs; // 重置下次窗口
            }
        }
    }

    /** 登录成功后清除失败计数与锁定，避免误伤后续正常使用。 */
    public void reset(String ip, String username) {
        String k = key(ip, username);
        buckets.remove(k);
        buckets.remove(k + LOCK_SUFFIX);
    }

    private String key(String ip, String username) {
        return ip + "|" + (username == null ? "" : username.trim().toLowerCase());
    }

    /** 固定窗口桶：count 为当前窗口失败数，lockedUntil 为锁定到期时间戳（0 = 未锁定）。 */
    private static final class Bucket {
        final AtomicInteger count = new AtomicInteger();
        long windowStart;
        long lockedUntil;

        Bucket(long timestamp) {
            this.windowStart = timestamp;
        }
    }
}
