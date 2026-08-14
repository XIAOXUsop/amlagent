package com.bank.aml.security;

import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

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
