-- V3：用户账户表（认证用户源迁移到数据库，替代内存用户）
-- 开发环境可用 ddl-auto=update，生产环境由 Flyway 保证 schema 一致。

CREATE TABLE sys_user (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(64) NOT NULL,
    -- BCrypt 加密后的密码哈希
    password   VARCHAR(100) NOT NULL,
    -- 角色：ANALYST / REVIEWER / ADMIN
    role       VARCHAR(32) NOT NULL,
    enabled    BIT(1)      NOT NULL DEFAULT b'1',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
