package com.bank.aml.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 生产环境管理员初始化：仅从环境变量创建管理员写入 {@code sys_user} 表。
 * <p>不内置任何演示账号；未设置环境变量时启动失败，避免留下默认口令后门。
 */
@Component
@Profile("prod")
public class ProdAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProdAdminSeeder.class);

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public ProdAdminSeeder(UserAccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String adminUser = System.getenv().getOrDefault("AML_ADMIN_USER", "admin");
        String adminPassword = System.getenv().get("AML_ADMIN_PASSWORD");
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException("prod 环境必须通过环境变量 AML_ADMIN_PASSWORD 设置管理员密码");
        }
        if (!repository.existsByUsername(adminUser)) {
            UserAccount account = new UserAccount();
            account.setUsername(adminUser);
            account.setPassword(passwordEncoder.encode(adminPassword));
            account.setRole("ADMIN");
            account.setEnabled(true);
            repository.save(account);
            log.info("已初始化生产管理员：{}", adminUser);
        }
    }
}
