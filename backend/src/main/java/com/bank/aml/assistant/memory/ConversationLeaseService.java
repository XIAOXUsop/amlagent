package com.bank.aml.assistant.memory;

import com.bank.aml.assistant.config.AssistantProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 多实例下的会话级租约；旧 owner 不能续租或释放新 owner 的锁。 */
@Service
public class ConversationLeaseService {
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final AssistantProperties properties;

    public ConversationLeaseService(StringRedisTemplate redis, AssistantProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public Optional<LeaseHandle> tryAcquire(String conversationId) {
        String key = key(conversationId);
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token,
                Duration.ofSeconds(properties.getLeaseTtlSeconds()));
        return Boolean.TRUE.equals(acquired) ? Optional.of(new LeaseHandle(key, token)) : Optional.empty();
    }

    public boolean renew(LeaseHandle handle) {
        Long result = redis.execute(RENEW, List.of(handle.key()), handle.token(),
                String.valueOf(Duration.ofSeconds(properties.getLeaseTtlSeconds()).toMillis()));
        return Long.valueOf(1).equals(result);
    }

    public boolean release(LeaseHandle handle) {
        Long result = redis.execute(RELEASE, List.of(handle.key()), handle.token());
        return Long.valueOf(1).equals(result);
    }

    private String key(String conversationId) { return "aml:assistant:lease:" + conversationId; }

    public record LeaseHandle(String key, String token) {}
}
