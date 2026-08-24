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
 * <p>纯内存实现适用于单实例；桶数量有硬上限，达到上限时先清理过期项，
 * 仍无容量则失败关闭，避免随机用户名造成无界内存增长。
 */
@Component
@EnableConfigurationProperties(LoginRateLimitProperties.class)
public class LoginRateLimiter {

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
        String k = key(ip, username);
        Bucket bucket = buckets.get(k);
        if (bucket != null && bucket.lockedUntil > now) {
            throw new TooManyRequestsException("登录尝试过于频繁，请稍后再试");
        }
        if (bucket != null && expired(bucket, now)) {
            buckets.remove(k, bucket);
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
        Bucket b = buckets.get(k);
        if (b == null) {
            b = createBucket(k, now);
        }
        synchronized (b) {
            if (now - b.windowStart > windowMs) {
                // 窗口过期：重置计数
                b.count.set(0);
                b.windowStart = now;
            }
            if (b.count.incrementAndGet() >= props.getMaxAttempts()) {
                b.lockedUntil = now + props.getLockSeconds() * 1000L;
                b.count.set(0);
                b.windowStart = now;
            }
        }
    }

    /** 登录成功后清除失败计数与锁定，避免误伤后续正常使用。 */
    public void reset(String ip, String username) {
        String k = key(ip, username);
        buckets.remove(k);
    }

    private synchronized Bucket createBucket(String key, long now) {
        Bucket existing = buckets.get(key);
        if (existing != null) {
            return existing;
        }
        int maxBuckets = Math.max(1, props.getMaxBuckets());
        if (buckets.size() >= maxBuckets) {
            buckets.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        }
        if (buckets.size() >= maxBuckets) {
            throw new TooManyRequestsException("登录保护容量已满，请稍后再试");
        }
        Bucket created = new Bucket(now);
        buckets.put(key, created);
        return created;
    }

    private boolean expired(Bucket bucket, long now) {
        long windowMs = Math.max(1, props.getWindowSeconds()) * 1000L;
        return bucket.lockedUntil <= now && now - bucket.windowStart > windowMs;
    }

    int trackedBucketCount() {
        return buckets.size();
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
