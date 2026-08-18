package com.bank.aml.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录速率限制配置：按"客户端 IP + 用户名"维度做固定窗口失败计数，
 * 窗口内失败次数超过 {@code maxAttempts} 后锁定 {@code lockSeconds} 秒。
 */
@ConfigurationProperties(prefix = "aml.security.login")
public class LoginRateLimitProperties {

    /** 窗口内最大失败次数；0 = 禁用速率限制 */
    private int maxAttempts = 5;

    /** 失败计数窗口（秒） */
    private int windowSeconds = 60;

    /** 触发限制后的锁定时长（秒） */
    private int lockSeconds = 300;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public int getLockSeconds() {
        return lockSeconds;
    }

    public void setLockSeconds(int lockSeconds) {
        this.lockSeconds = lockSeconds;
    }
}
