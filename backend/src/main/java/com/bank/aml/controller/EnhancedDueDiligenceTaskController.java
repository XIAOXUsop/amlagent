package com.bank.aml.controller;

import com.bank.aml.review.EnhancedDueDiligenceService;
import com.bank.aml.review.EnhancedDueDiligenceView;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 跨案件的补充尽调工作台。 */
@RestController
@RequestMapping("/api/edd")
public class EnhancedDueDiligenceTaskController {

    private final EnhancedDueDiligenceService service;

    public EnhancedDueDiligenceTaskController(EnhancedDueDiligenceService service) {
        this.service = service;
    }

    /** 分析员只看本人待办；ADMIN 可显式查看全部待办。 */
    @GetMapping("/tasks")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public List<EnhancedDueDiligenceView> tasks(@RequestParam(defaultValue = "false") boolean all,
            Authentication authentication) {
        boolean admin = authentication.getAuthorities()
            .stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return service.pendingTasks(authentication.getName(), admin && all);
    }

    @GetMapping("/assignees")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public List<AssigneeView> assignees() {
        return service.eligibleAssignees()
            .stream()
            .map(user -> new AssigneeView(user.username(), user.role()))
            .toList();
    }

    public record AssigneeView(String username, String role) {
    }

}
