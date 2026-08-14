package com.bank.aml.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * Spring Security 配置：无状态 JWT 认证，内置三角色用户。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 内置演示用户：ANALYST / REVIEWER / ADMIN（仅非 prod 环境注册） */
    @Bean
    @Profile("!prod")
    public UserDetailsService demoUserDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("analyst").password(encoder.encode("analyst123")).roles("ANALYST").build(),
                User.withUsername("reviewer").password(encoder.encode("reviewer123")).roles("REVIEWER").build(),
                User.withUsername("admin").password(encoder.encode("admin123")).roles("ADMIN").build());
    }

    /** 生产环境用户：仅从环境变量初始化管理员，不内置演示账号 */
    @Bean
    @Profile("prod")
    public UserDetailsService prodUserDetailsService(PasswordEncoder encoder) {
        String adminUser = System.getenv().getOrDefault("AML_ADMIN_USER", "admin");
        String adminPassword = System.getenv().getOrDefault("AML_ADMIN_PASSWORD", "");
        if (adminPassword.isBlank()) {
            throw new IllegalStateException("prod 环境必须通过环境变量 AML_ADMIN_PASSWORD 设置管理员密码");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(adminUser).password(encoder.encode(adminPassword)).roles("ADMIN").build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http.csrf(csrf -> csrf
                        // HttpOnly Cookie 认证下，浏览器会自动携带认证 Cookie，必须启用 CSRF 防护。
                        // 可读的 XSRF-TOKEN Cookie 供前端读取写入 X-XSRF-TOKEN header。
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers("/api/auth/login", "/actuator/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/logout", "/actuator/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                // 未认证返回 401；已认证但权限不足由 @PreAuthorize 抛 AccessDeniedException（全局处理 403）
                .exceptionHandling(eh -> eh.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
