package com.bank.aml.security;

import com.bank.aml.TestProperties;
import com.bank.aml.audit.AuditService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);

    private final JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);

    private final LoginRateLimiter rateLimiter = mock(LoginRateLimiter.class);

    private final AuditService audit = mock(AuditService.class);

    private final AuthenticationAccountService accountService = mock(AuthenticationAccountService.class);

    private final AuthController controller = new AuthController(authenticationManager, tokenProvider, rateLimiter,
            audit, accountService, TestProperties.aml());

    @Test
    void enabledUserIsAuthenticatedBySpringSecurityAndReceivesCookie() {
        var user = User.withUsername("analyst").password("encoded").roles("ANALYST").build();
        var authenticated = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        var request = request();
        var response = new MockHttpServletResponse();
        when(authenticationManager.authenticate(any())).thenReturn(authenticated);
        when(accountService.tokenVersion("analyst")).thenReturn(0);
        when(tokenProvider.createToken("analyst", "ANALYST", 0)).thenReturn("signed-token");

        AuthController.LoginResponse body = controller.login(new AuthController.LoginRequest("analyst", "secret"),
                request, response);

        assertThat(body.username()).isEqualTo("analyst");
        assertThat(body.role()).isEqualTo("ANALYST");
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
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(() -> controller.login(new AuthController.LoginRequest("disabled-user", "correct-password"),
                request, response))
            .isInstanceOf(DisabledException.class);

        assertThat(response.getCookie("aml_token")).isNull();
        verify(rateLimiter).recordFailure("127.0.0.1", "disabled-user");
    }

    @Test
    void spoofedForwardedForCannotBypassRateLimitIdentity() {
        var request = request();
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        var response = new MockHttpServletResponse();
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        assertThatThrownBy(
                () -> controller.login(new AuthController.LoginRequest("analyst", "wrong"), request, response))
            .isInstanceOf(DisabledException.class);

        verify(rateLimiter).checkBlocked("127.0.0.1", "analyst");
        verify(rateLimiter).recordFailure("127.0.0.1", "analyst");
    }

    @Test
    void logoutRevokesOutstandingTokensByIncrementingTokenVersion() {
        var response = new MockHttpServletResponse();
        var auth = UsernamePasswordAuthenticationToken
            .authenticated(User.withUsername("analyst").password("x").roles("ANALYST").build(), null, List.of());

        controller.logout(auth, response);

        verify(accountService).revokeTokens("analyst");
        assertThat(response.getCookie("aml_token")).isNotNull();
    }

    @Test
    void anonymousLogoutOnlyClearsCookieWithoutRevocation() {
        var response = new MockHttpServletResponse();

        controller.logout(null, response);

        verifyNoInteractions(accountService);
        assertThat(response.getCookie("aml_token")).isNotNull();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

}
