package com.bank.aml.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Spring Security 配置：无状态 JWT 认证与基于数据库用户的角色授权。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 统一走 Spring Security 认证链，确保密码、禁用、锁定、过期等账号状态全部生效。 */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * 认证用户源已迁移至数据库（{@link DbUserDetailsService} + {@code sys_user} 表）。
     * 演示账号由 {@link UserSeeder} 在非 prod 启动时写入；生产环境由 {@link ProdAdminSeeder} 从环境变量初始化。
     */

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http.csrf(csrf -> csrf
                        // HttpOnly Cookie 认证下，浏览器会自动携带认证 Cookie，必须启用 CSRF 防护。
                        // 可读的 XSRF-TOKEN Cookie 供前端读取写入 X-XSRF-TOKEN header。
                        .csrfTokenRepository(statelessCookieCsrfTokenRepository())
                        // Cookie 存的是原始 token，必须用明文 handler 回读，
                        // 否则默认 XorCsrfTokenRequestAttributeHandler 会 XOR 编码导致 SPA 头回传被拒绝。
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/auth/login", "/api/auth/csrf", "/actuator/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Actuator 仅 health/info 公开，其余（含 prometheus）限 ADMIN
                        .requestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/csrf",
                                "/actuator/health", "/actuator/info",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // 未认证返回 401；已认证但权限不足由 @PreAuthorize 抛 AccessDeniedException（全局处理 403）
                .exceptionHandling(eh -> eh.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 无状态 JWT 下，SessionManagementFilter 每次请求都会触发 CsrfAuthenticationStrategy 清除 CSRF Cookie
     * （`saveToken(null)`），导致前端登录后首个 GET 就把 XSRF-TOKEN 清空。这里包一层，
     * 忽略对 null token 的落盘，只保留正常签发，从而让 Cookie 中的原始 token 在整个会话内保持稳定。
     */
    private CsrfTokenRepository statelessCookieCsrfTokenRepository() {
        CookieCsrfTokenRepository delegate = CookieCsrfTokenRepository.withHttpOnlyFalse();
        return new CsrfTokenRepository() {
            @Override
            public CsrfToken generateToken(HttpServletRequest request) {
                return delegate.generateToken(request);
            }

            @Override
            public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
                if (token != null) {
                    delegate.saveToken(token, request, response);
                }
                // token == null（认证时清除）直接忽略，避免 Cookie 被 SessionManagementFilter 清空
            }

            @Override
            public CsrfToken loadToken(HttpServletRequest request) {
                return delegate.loadToken(request);
            }
        };
    }
}
