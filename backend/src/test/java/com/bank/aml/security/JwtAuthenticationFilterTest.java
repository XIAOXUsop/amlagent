package com.bank.aml.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userDetailsService);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void alsoAuthenticatesSseAsyncDispatch() throws Exception {
        Claims claims = mock(Claims.class);
        when(tokenProvider.validate("async-token")).thenReturn(true);
        when(tokenProvider.parse("async-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("admin");
        when(userDetailsService.loadUserByUsername("admin")).thenReturn(
                User.withUsername("admin").password("x").roles("ADMIN").build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.ASYNC);
        request.addHeader("Authorization", "Bearer async-token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_ADMIN");
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void currentDatabaseRoleOverridesStaleRoleInToken() throws Exception {
        Claims claims = mock(Claims.class);
        when(tokenProvider.validate("token")).thenReturn(true);
        when(tokenProvider.parse("token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("alice");
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(
                User.withUsername("alice").password("x").roles("REVIEWER").build());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_REVIEWER");
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deletedUserTokenDoesNotAuthenticate() throws Exception {
        Claims claims = mock(Claims.class);
        when(tokenProvider.validate("token")).thenReturn(true);
        when(tokenProvider.parse("token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("deleted");
        when(userDetailsService.loadUserByUsername("deleted"))
                .thenThrow(new UsernameNotFoundException("deleted"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
