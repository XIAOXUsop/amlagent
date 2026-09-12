package com.bank.aml.security;

import com.bank.aml.common.exception.TooManyRequestsException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Redis 实现的登录限流语义：锁定、滑动窗口计数、Redis 故障 fail-open。 */
class LoginRateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private final LoginRateLimitProperties props = new LoginRateLimitProperties();

    private final LoginRateLimiter limiter = new LoginRateLimiter(props, redis);

    @BeforeEach
    void setUp() {
        lenient().doReturn(values).when(redis).opsForValue();
        lenient().when(redis.hasKey(anyString())).thenReturn(false);
        lenient().when(values.increment(anyString())).thenReturn(1L);
    }

    @Test
    void blockedIpIsRejectedBeforePasswordVerification() {
        when(redis.hasKey("aml:login:lock:10.0.0.1|alice")).thenReturn(true);

        assertThatThrownBy(() -> limiter.checkBlocked("10.0.0.1", "alice"))
            .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void reachingFailureThresholdSetsLockKeyAndClearsCounter() {
        props.setMaxAttempts(3);
        when(values.increment("aml:login:fail:10.0.0.1|alice")).thenReturn(3L);

        limiter.recordFailure("10.0.0.1", "alice");

        verify(values).set(eq("aml:login:lock:10.0.0.1|alice"), eq("1"), any(Duration.class));
        verify(redis).delete("aml:login:fail:10.0.0.1|alice");
    }

    @Test
    void everyFailureRefreshesWindowTtlAgainstTimerDilution() {
        props.setMaxAttempts(5);
        when(values.increment(anyString())).thenReturn(2L);

        limiter.recordFailure("10.0.0.1", "bob");

        verify(values).increment("aml:login:fail:10.0.0.1|bob");
        verify(redis).expire(eq("aml:login:fail:10.0.0.1|bob"), any(Duration.class));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisOutageFailsOpenInsteadOfLockingEveryoneOut() {
        when(redis.hasKey(anyString())).thenThrow(new IllegalStateException("redis down"));
        doThrow(new IllegalStateException("redis down")).when(values).increment(anyString());

        assertThatCode(() -> limiter.checkBlocked("10.0.0.1", "alice")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.recordFailure("10.0.0.1", "alice")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.reset("10.0.0.1", "alice")).doesNotThrowAnyException();
    }

    @Test
    void successfulLoginClearsFailureAndLockKeys() {
        limiter.reset("10.0.0.1", "Alice");

        verify(redis).delete("aml:login:fail:10.0.0.1|alice");
        verify(redis).delete("aml:login:lock:10.0.0.1|alice");
    }

    @Test
    void disabledLimiterIsNoOp() {
        props.setMaxAttempts(0);

        assertThatCode(() -> limiter.checkBlocked("10.0.0.1", "alice")).doesNotThrowAnyException();
        verify(redis, never()).hasKey(anyString());
        verify(redis, never()).delete(contains("alice"));
    }

}
