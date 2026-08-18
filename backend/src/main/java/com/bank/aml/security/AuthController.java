package com.bank.aml.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录接口：校验用户名密码，签发 JWT 写入 HttpOnly Cookie，不向响应体返回长期 JWT。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final LoginRateLimiter loginRateLimiter;
    private final boolean cookieSecure;

    public AuthController(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder,
                          JwtTokenProvider tokenProvider, LoginRateLimiter loginRateLimiter,
                          @Value("${aml.security.cookie-secure:false}") boolean cookieSecure) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.loginRateLimiter = loginRateLimiter;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletRequest httpRequest,
                                     HttpServletResponse response) {
        String ip = clientIp(httpRequest);
        // 先检查是否已被锁定（避免仍进入昂贵的 BCrypt 校验）
        loginRateLimiter.checkBlocked(ip, req.username());
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(req.username());
        } catch (UsernameNotFoundException e) {
            loginRateLimiter.recordFailure(ip, req.username());
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            loginRateLimiter.recordFailure(ip, req.username());
            throw new IllegalArgumentException("用户名或密码错误");
        }
        loginRateLimiter.reset(ip, req.username());
        String role = user.getAuthorities().stream()
                .findFirst().map(a -> a.getAuthority().replace("ROLE_", "")).orElse("ANALYST");
        String token = tokenProvider.createToken(user.getUsername(), role);
        // 纯 HttpOnly Cookie：JWT 不进入响应体 / localStorage / URL，降低 XSS 窃取与日志泄露风险
        Cookie cookie = new Cookie("aml_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setPath("/");
        cookie.setMaxAge(24 * 3600); // 与 JWT 有效期一致
        response.addCookie(cookie);
        return Map.of("username", user.getUsername(), "role", role);
    }

    /** 取客户端真实 IP（识别反向代理透传 X-Forwarded-For 的第一个地址），并防御异常值 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (first.length() <= 64) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    /** 当前登录用户（用于前端刷新后恢复登录态；未认证由 Security 返回 401） */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .findFirst().map(a -> a.getAuthority().replace("ROLE_", "")).orElse("ANALYST");
        return Map.of("username", authentication.getName(), "role", role);
    }

    /** 强制生成 CSRF Token Cookie（登录后调用一次，供前端写请求携带 X-XSRF-TOKEN） */
    @GetMapping("/csrf")
    public Map<String, Object> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "parameterName", token.getParameterName());
    }

    /** 登出：清除认证 Cookie */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie("aml_token", null);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setPath("/");
        cookie.setMaxAge(0); // 立即过期
        response.addCookie(cookie);
        return Map.of("ok", true);
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }
}
