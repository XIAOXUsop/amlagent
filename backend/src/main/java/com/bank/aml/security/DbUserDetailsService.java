package com.bank.aml.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于数据库的用户认证源：从 {@code sys_user} 表加载用户与角色。
 * <p>替代演示用 {@code InMemoryUserDetailsManager}，支持真实用户持久化。
 * 密码以 BCrypt 哈希存于数据库，登录校验由 {@code AuthenticationManager} 完成。
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private final UserAccountRepository repository;

    public DbUserDetailsService(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在：" + username));
        return User.withUsername(account.getUsername())
                .password(account.getPassword())
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole())))
                .disabled(!account.isEnabled())
                .build();
    }
}
