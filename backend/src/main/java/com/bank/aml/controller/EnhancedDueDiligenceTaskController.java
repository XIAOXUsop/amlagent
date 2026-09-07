package com.bank.aml.controller;

import com.bank.aml.review.EnhancedDueDiligenceService;
import com.bank.aml.review.EnhancedDueDiligenceView;
import com.bank.aml.security.UserAccountRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 跨案件的补充尽调工作台。 */
@RestController
@RequestMapping("/api/edd")
public class EnhancedDueDiligenceTaskController {

    private final EnhancedDueDiligenceService service;
    private final UserAccountRepository userAccounts;

    public EnhancedDueDiligenceTaskController(EnhancedDueDiligenceService service,
                                              UserAccountRepository userAccounts) {
        this.service = service;
        this.userAccounts = userAccounts;
    }

    /** 分析员只看本人待办；ADMIN 可显式查看全部待办。 */
    @GetMapping("/tasks")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public List<EnhancedDueDiligenceView> tasks(
            @RequestParam(defaultValue = "false") boolean all, Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return service.pendingTasks(authentication.getName(), admin && all);
    }

    @GetMapping("/assignees")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public List<AssigneeView> assignees() {
        return userAccounts.findByRoleAndEnabledTrueOrderByUsernameAsc("ANALYST").stream()
                .map(user -> new AssigneeView(user.getUsername(), "ANALYST"))
                .toList();
    }

    public record AssigneeView(String username, String role) {
    }
}
