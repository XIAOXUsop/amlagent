package com.bank.aml.security;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 认证用例所需的账号状态访问，避免 HTTP Controller 直接操作持久化接口。 */
@Service
public class AuthenticationAccountService {

    private final UserAccountRepository userAccounts;

    public AuthenticationAccountService(UserAccountRepository userAccounts) {
        this.userAccounts = userAccounts;
    }

    @Transactional(readOnly = true)
    public int tokenVersion(String username) {
        return userAccounts.findByUsername(username).map(UserAccount::getTokenVersion).orElse(0);
    }

    @Transactional
    public void revokeTokens(String username) {
        userAccounts.findByUsername(username).ifPresent(account -> {
            account.revokeTokens();
            userAccounts.save(account);
        });
    }

}
