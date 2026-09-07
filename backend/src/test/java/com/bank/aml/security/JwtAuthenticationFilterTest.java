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
    private final UserAccountRepository userAccounts = mock(UserAccountRepository.class);
    private final JwtAuthenticationFilter filter =
            new JwtAuthenticationFilter(tokenProvider, userDetailsService, userAccounts);

    private UserAccount dbAccount(String username, int tokenVersion) {
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPassword("x");
        account.setRole("ADMIN");
        account.setTokenVersion(tokenVersion);
        return account;
    }

    private void stubAccount(String username, int tokenVersion) {
        when(userAccounts.findByUsername(username))
                .thenReturn(java.util.Optional.of(dbAccount(username, tokenVersion)));
        org.mockito.Mockito.when(tokenProvider.tokenVersion(org.mockito.ArgumentMatchers.any()))
                .thenReturn(tokenVersion);
    }

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
        stubAccount("admin", 0);
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
        stubAccount("alice", 0);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_REVIEWER");
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void staleTokenVersionIsRejectedAfterRevocation() throws Exception {
        Claims claims = mock(Claims.class);
        when(tokenProvider.validate("token")).thenReturn(true);
        when(tokenProvider.parse("token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("alice");
        when(userDetailsService.loadUserByUsername("alice")).thenReturn(
                User.withUsername("alice").password("x").roles("REVIEWER").build());
        // 数据库已递增到 1（登出/吊销），令牌仍是旧版本 0
        when(userAccounts.findByUsername("alice"))
                .thenReturn(java.util.Optional.of(dbAccount("alice", 1)));
        when(tokenProvider.tokenVersion(org.mockito.ArgumentMatchers.any())).thenReturn(0);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingAccountTokenVersionFailsClosed() throws Exception {
        Claims claims = mock(Claims.class);
        when(tokenProvider.validate("token")).thenReturn(true);
        when(tokenProvider.parse("token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("ghost");
        when(userDetailsService.loadUserByUsername("ghost")).thenReturn(
                User.withUsername("ghost").password("x").roles("ADMIN").build());
        when(userAccounts.findByUsername("ghost")).thenReturn(java.util.Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
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
