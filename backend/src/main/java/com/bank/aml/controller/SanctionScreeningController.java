package com.bank.aml.controller;

import com.bank.aml.sanction.SanctionScreeningResult;
import com.bank.aml.sanction.SanctionScreeningService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 可解释制裁名单筛查接口。 */
@RestController
@RequestMapping("/api/sanctions")
public class SanctionScreeningController {

    private final SanctionScreeningService service;

    public SanctionScreeningController(SanctionScreeningService service) {
        this.service = service;
    }

    @GetMapping("/screen/{customerId}")
    public SanctionScreeningResult screen(@PathVariable String customerId) {
        return service.screen(customerId);
    }

    @PostMapping("/screen/{customerId}/review")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public SanctionScreeningResult review(@PathVariable String customerId, @Valid @RequestBody ReviewRequest request) {
        String reviewer = SecurityContextHolder.getContext().getAuthentication().getName();
        return service.review(customerId, request.candidateFingerprint(), request.decision(), request.comment(),
                request.expectedRevision(), reviewer);
    }

    public record ReviewRequest(
            @NotBlank(message = "候选指纹不能为空") @Size(min = 64, max = 64, message = "候选指纹格式错误")
            String candidateFingerprint,
            @NotBlank(message = "核验决定不能为空") String decision,
            @Size(max = 500, message = "核验意见最多 500 字") String comment,
            int expectedRevision) {
    }
}
