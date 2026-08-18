package com.bank.aml.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示用户种子数据：应用启动时若用户表为空，幂等写入三角色演示账号（密码 BCrypt 加密）。
 * <p>仅非 prod 环境注册；生产环境通过环境变量初始化管理员（见 {@link SecurityConfig}）。
 */
@Component
@Profile("!prod")
public class UserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public UserSeeder(UserAccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfMissing("analyst", "analyst123", "ANALYST");
        seedIfMissing("reviewer", "reviewer123", "REVIEWER");
        seedIfMissing("admin", "admin123", "ADMIN");
    }

    private void seedIfMissing(String username, String rawPassword, String role) {
        if (repository.existsByUsername(username)) {
            return; // 幂等：已存在则不覆盖，保留用户后续可能修改的密码
        }
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPassword(passwordEncoder.encode(rawPassword));
        account.setRole(role);
        account.setEnabled(true);
        repository.save(account);
        log.info("已写入演示用户：{}（{}）", username, role);
    }
}
