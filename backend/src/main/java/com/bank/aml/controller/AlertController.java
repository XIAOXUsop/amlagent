package com.bank.aml.controller;

import com.bank.aml.dto.CaseDto;
import com.bank.aml.investigation.AlertView;
import com.bank.aml.investigation.CaseIntakeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 以客户为单位的原始预警分诊入口。 */
@RestController
@RequestMapping("/api/alerts")
@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
public class AlertController {
    private final CaseIntakeService intake;

    public AlertController(CaseIntakeService intake) { this.intake = intake; }

    @GetMapping
    public List<AlertView> inbox() { return intake.inbox(); }

    @PostMapping
    public AlertView create(@Valid @RequestBody CreateAlertRequest request, Authentication authentication) {
        return intake.createAlert(request.externalAlertId(), request.customerId(), request.ruleCode(),
                request.scenarioCode(), request.hitReason(), request.occurredAt(), authentication.getName());
    }

    @GetMapping("/{alertId}/candidate-cases")
    public List<CaseDto> candidateCases(@PathVariable Long alertId) {
        return intake.candidateCases(alertId).stream().map(CaseDto::from).toList();
    }

    @PostMapping("/{alertId}/create-case")
    public CaseDto createCase(@PathVariable Long alertId, @Valid @RequestBody CaseActionRequest request,
                              Authentication authentication) {
        return CaseDto.from(intake.createCaseFromAlert(alertId, request.expectedRevision(),
                request.autoProcess() == null || request.autoProcess(), authentication.getName(),
                Boolean.TRUE.equals(request.enableExplanationPolicy())));
    }

    @PostMapping("/{alertId}/link")
    public AlertView link(@PathVariable Long alertId, @Valid @RequestBody LinkRequest request,
                          Authentication authentication) {
        return intake.linkToCase(alertId, request.caseId(), request.expectedRevision(),
                request.reason(), authentication.getName());
    }

    @PostMapping("/{alertId}/split")
    public CaseDto split(@PathVariable Long alertId, @Valid @RequestBody SplitRequest request,
                         Authentication authentication) {
        return CaseDto.from(intake.splitToNewCase(alertId, request.expectedRevision(),
                request.autoProcess() == null || request.autoProcess(), request.reason(), authentication.getName()));
    }

    @PostMapping("/{alertId}/duplicate")
    public AlertView duplicate(@PathVariable Long alertId, @Valid @RequestBody DuplicateRequest request,
                               Authentication authentication) {
        return intake.closeDuplicate(alertId, request.expectedRevision(), request.reason(), authentication.getName());
    }

    public record CreateAlertRequest(
            @NotBlank @Size(min = 3, max = 64) String externalAlertId,
            @NotBlank @Size(max = 32) String customerId,
            @NotBlank @Size(min = 3, max = 64) String ruleCode,
            @Size(max = 64) String scenarioCode,
            @NotBlank @Size(min = 10, max = 500) String hitReason,
            LocalDateTime occurredAt) { }

    public record CaseActionRequest(@PositiveOrZero int expectedRevision, Boolean autoProcess, Boolean enableExplanationPolicy) { }
    public record LinkRequest(@NotNull Long caseId, @PositiveOrZero int expectedRevision,
                              @NotBlank @Size(min = 10, max = 500) String reason) { }
    public record SplitRequest(@PositiveOrZero int expectedRevision, Boolean autoProcess,
                               @NotBlank @Size(min = 10, max = 500) String reason) { }
    public record DuplicateRequest(@PositiveOrZero int expectedRevision,
                                   @NotBlank @Size(min = 10, max = 500) String reason) { }
}
