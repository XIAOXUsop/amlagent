package com.bank.aml.security;

import com.bank.aml.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    @Test
    void randomUsernamesCannotGrowBucketsBeyondConfiguredLimit() {
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setMaxBuckets(2);
        LoginRateLimiter limiter = new LoginRateLimiter(properties);

        limiter.recordFailure("127.0.0.1", "user-1");
        limiter.recordFailure("127.0.0.1", "user-2");

        assertThatThrownBy(() -> limiter.recordFailure("127.0.0.1", "user-3"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThat(limiter.trackedBucketCount()).isEqualTo(2);
    }

    @Test
    void successfulLoginReleasesBucketCapacity() {
        LoginRateLimitProperties properties = new LoginRateLimitProperties();
        properties.setMaxBuckets(1);
        LoginRateLimiter limiter = new LoginRateLimiter(properties);

        limiter.recordFailure("127.0.0.1", "user-1");
        limiter.reset("127.0.0.1", "user-1");
        limiter.recordFailure("127.0.0.1", "user-2");

        assertThat(limiter.trackedBucketCount()).isEqualTo(1);
    }
}
