package com.bank.aml.controller;

import com.bank.aml.review.EnhancedDueDiligenceEvidenceSubmission;
import com.bank.aml.review.EnhancedDueDiligenceService;
import com.bank.aml.review.EnhancedDueDiligenceView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 补充尽调任务查询与材料提交接口。 */
@RestController
@RequestMapping("/api/cases/{caseId}/edd")
public class EnhancedDueDiligenceController {

    private final EnhancedDueDiligenceService service;

    public EnhancedDueDiligenceController(EnhancedDueDiligenceService service) {
        this.service = service;
    }

    @GetMapping
    public List<EnhancedDueDiligenceView> list(@PathVariable Long caseId) {
        return service.list(caseId);
    }

    @PostMapping("/{requestId}/submit")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public EnhancedDueDiligenceView submit(@PathVariable Long caseId, @PathVariable Long requestId,
            @Valid @RequestBody SubmitRequest request, Authentication authentication) {
        String analyst = authentication.getName();
        boolean admin = authentication.getAuthorities()
            .stream()
            .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        EnhancedDueDiligenceView submitted = service.submitResponse(caseId, requestId, request.expectedRevision(),
                request.responseSummary(), request.evidenceItems(), analyst, admin);
        return submitted;
    }

    @PostMapping("/{requestId}/cancel")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public EnhancedDueDiligenceView cancel(@PathVariable Long caseId, @PathVariable Long requestId,
            @Valid @RequestBody CancelRequest request) {
        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        EnhancedDueDiligenceView cancelled = service.cancel(caseId, requestId, request.expectedRevision(),
                request.reason(), reviewer);
        return cancelled;
    }

    public record SubmitRequest(@PositiveOrZero int expectedRevision,
            @NotBlank(message = "材料说明不能为空") @Size(min = 10, max = 2000,
                    message = "材料说明需为 10 ~ 2000 个字符") String responseSummary,
            @NotEmpty(message = "证据元数据不能为空") @Size(max = 20,
                    message = "证据元数据最多 20 项") List<@Valid EnhancedDueDiligenceEvidenceSubmission> evidenceItems) {
    }

    public record CancelRequest(@PositiveOrZero int expectedRevision,
            @NotBlank(message = "撤销原因不能为空") @Size(min = 10, max = 500, message = "撤销原因需为 10 ~ 500 个字符") String reason) {
    }

}
