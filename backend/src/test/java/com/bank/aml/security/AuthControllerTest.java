package com.bank.aml.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);
    private final AuthController controller = new AuthController(authenticationManager, tokenProvider, rateLimiter, false);

    @Test
    void enabledUserIsAuthenticatedBySpringSecurityAndReceivesCookie() {
        var user = User.withUsername("analyst")
                .password("encoded")
                .roles("ANALYST")
                .build();
        var authenticated = UsernamePasswordAuthenticationToken.authenticated(
                user, null, user.getAuthorities());
        var request = request();
        var response = new MockHttpServletResponse();
        when(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any())).thenReturn(authenticated);
        when(tokenProvider.createToken("analyst", "ANALYST")).thenReturn("signed-token");

        Map<String, Object> body = controller.login(
                new AuthController.LoginRequest("analyst", "secret"), request, response);

        assertThat(body).containsEntry("username", "analyst").containsEntry("role", "ANALYST");
        Cookie cookie = response.getCookie("aml_token");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("signed-token");
        assertThat(cookie.isHttpOnly()).isTrue();
        verify(rateLimiter).reset("127.0.0.1", "analyst");
    }

    @Test
    void disabledUserCannotLoginEvenWhenPasswordWouldOtherwiseMatch() {
        var request = request();
        var response = new MockHttpServletResponse();
        when(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("disabled-user", "correct-password"), request, response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("用户名或密码错误");

        assertThat(response.getCookie("aml_token")).isNull();
        verify(rateLimiter).recordFailure("127.0.0.1", "disabled-user");
    }

    @Test
    void spoofedForwardedForCannotBypassRateLimitIdentity() {
        var request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        var response = new MockHttpServletResponse();
        when(authenticationManager.authenticate(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> controller.login(
                new AuthController.LoginRequest("analyst", "wrong"), request, response))
                .isInstanceOf(IllegalArgumentException.class);

        verify(rateLimiter).checkBlocked("127.0.0.1", "analyst");
        verify(rateLimiter).recordFailure("127.0.0.1", "analyst");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
