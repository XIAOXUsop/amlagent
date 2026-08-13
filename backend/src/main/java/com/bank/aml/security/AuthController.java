package com.bank.aml.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录接口：校验用户名密码，签发 JWT。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthController(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder,
                          JwtTokenProvider tokenProvider) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        UserDetails user;
        try {
            user = userDetailsService.loadUserByUsername(req.username());
        } catch (UsernameNotFoundException e) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        String role = user.getAuthorities().stream()
                .findFirst().map(a -> a.getAuthority().replace("ROLE_", "")).orElse("ANALYST");
        String token = tokenProvider.createToken(user.getUsername(), role);
        // 设置 HttpOnly Cookie，供 SSE（EventSource 无法自定义 Header）使用，避免 JWT 进入 URL/日志
        Cookie cookie = new Cookie("aml_token", token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(24 * 3600); // 与 JWT 有效期一致
        response.addCookie(cookie);
        return Map.of("token", token, "username", user.getUsername(), "role", role);
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }
}
