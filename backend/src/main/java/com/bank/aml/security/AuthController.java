package com.bank.aml.security;

import com.bank.aml.audit.AuditService;
import com.bank.aml.config.AmlProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录接口：校验用户名密码，签发 JWT 写入 HttpOnly Cookie，不向响应体返回长期 JWT。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;

    private final JwtTokenProvider tokenProvider;

    private final LoginRateLimiter loginRateLimiter;

    private final AuditService audit;

    private final AuthenticationAccountService accountService;

    private final boolean cookieSecure;

    public AuthController(AuthenticationManager authenticationManager, JwtTokenProvider tokenProvider,
            LoginRateLimiter loginRateLimiter, AuditService audit, AuthenticationAccountService accountService,
            AmlProperties properties) {
        this.authenticationManager = authenticationManager;
        this.tokenProvider = tokenProvider;
        this.loginRateLimiter = loginRateLimiter;
        this.audit = audit;
        this.accountService = accountService;
        this.cookieSecure = properties.security().cookieSecure();
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String ip = clientIp(httpRequest);
        // 先检查是否已被锁定（避免仍进入昂贵的 BCrypt 校验）
        loginRateLimiter.checkBlocked(ip, req.username());
        Authentication authentication;
        try {
            authentication = authenticationManager
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(req.username(), req.password()));
        }
        catch (AuthenticationException e) {
            loginRateLimiter.recordFailure(ip, req.username());
            // 登录失败审计：只记用户名与来源 IP，不记密码与失败细节
            audit.record(req.username(), "LOGIN_FAILURE", "USER", req.username(), "FAILURE", null, ip);
            throw e;
        }
        UserDetails user = (UserDetails) authentication.getPrincipal();
        loginRateLimiter.reset(ip, req.username());
        String role = user.getAuthorities()
            .stream()
            .findFirst()
            .map(a -> a.getAuthority().replace("ROLE_", ""))
            .orElse("ANALYST");
        int tokenVersion = accountService.tokenVersion(user.getUsername());
        String token = tokenProvider.createToken(user.getUsername(), role, tokenVersion);
        // 纯 HttpOnly Cookie：JWT 不进入响应体 / localStorage / URL，降低 XSS 窃取与日志泄露风险
        Cookie cookie = new Cookie("aml_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setPath("/");
        cookie.setMaxAge(24 * 3600); // 与 JWT 有效期一致
        response.addCookie(cookie);
        audit.record(user.getUsername(), "LOGIN_SUCCESS", "USER", user.getUsername(), "SUCCESS", null, ip);
        return new LoginResponse(user.getUsername(), role);
    }

    /**
     * 只信任 Servlet 容器解析后的远端地址。
     * <p>
     * 应用不能直接信任客户端可伪造的 X-Forwarded-For；默认配置不解析 Forwarded 头。
     * 如部署在受控代理后，应由网关覆盖这些头并在容器层配置明确的可信代理范围。
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /** 当前登录用户（用于前端刷新后恢复登录态；未认证由 Security 返回 401） */
    @GetMapping("/me")
    public CurrentUserResponse me(Authentication authentication) {
        String role = authentication.getAuthorities()
            .stream()
            .findFirst()
            .map(a -> a.getAuthority().replace("ROLE_", ""))
            .orElse("ANALYST");
        return new CurrentUserResponse(authentication.getName(), role);
    }

    /** 强制生成 CSRF Token Cookie（登录后调用一次，供前端写请求携带 X-XSRF-TOKEN） */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getParameterName());
    }

    /** 登出：递增令牌版本吊销已签发 JWT，并清除认证 Cookie。 */
    @PostMapping("/logout")
    public LogoutResponse logout(Authentication authentication, HttpServletResponse response) {
        if (authentication != null && authentication.isAuthenticated()) {
            accountService.revokeTokens(authentication.getName());
            audit.record(authentication.getName(), "LOGOUT", "USER", authentication.getName(), "SUCCESS", null, null);
        }
        Cookie cookie = new Cookie("aml_token", null);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setAttribute("SameSite", "Lax");
        cookie.setPath("/");
        cookie.setMaxAge(0); // 立即过期
        response.addCookie(cookie);
        return new LogoutResponse(true);
    }

    public record LoginRequest(@NotBlank(message = "用户名不能为空") @Size(max = 64) String username,
            @NotBlank(message = "密码不能为空") @Size(max = 256) String password) {
    }

    public record LoginResponse(String username, String role) {
    }

    public record CurrentUserResponse(String username, String role) {
    }

    public record CsrfResponse(String headerName, String parameterName) {
    }

    public record LogoutResponse(boolean ok) {
    }

}
