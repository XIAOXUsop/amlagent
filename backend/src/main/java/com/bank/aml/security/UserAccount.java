package com.bank.aml.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 平台用户账户（数据库存储）。
 * <p>保存用户名、BCrypt 加密后的密码哈希与角色，替代演示用的内存用户。
 * 密码绝不以明文存放；登录校验由 {@link DbUserDetailsService} 完成。
 */
@Entity
@Table(name = "sys_user")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录用户名（唯一） */
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt 加密后的密码哈希 */
    @Column(nullable = false, length = 100)
    private String password;

    /** 角色：ANALYST / REVIEWER / ADMIN */
    @Column(nullable = false, length = 32)
    private String role;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * 令牌版本号：登出/改密/禁用等吊销场景递增。
     * JWT 内嵌签发时的版本号，每次请求与数据库比对，不匹配即拒绝——无需黑名单即可吊销历史令牌。
     */
    @Column(nullable = false)
    private int tokenVersion = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(int tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    /** 吊销当前已签发的全部令牌（登出/改密/禁用后调用）。 */
    public void revokeTokens() {
        this.tokenVersion = this.tokenVersion + 1;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
