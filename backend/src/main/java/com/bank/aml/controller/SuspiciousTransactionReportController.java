package com.bank.aml.controller;

import com.bank.aml.reporting.SuspiciousTransactionReportService;
import com.bank.aml.reporting.SuspiciousTransactionReportView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
public class SuspiciousTransactionReportController {
    private final SuspiciousTransactionReportService service;

    public SuspiciousTransactionReportController(SuspiciousTransactionReportService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    public List<SuspiciousTransactionReportView> pending() { return service.pending(); }

    @GetMapping("/{caseId}")
    public SuspiciousTransactionReportView get(@PathVariable Long caseId) { return service.get(caseId); }

    /** 登记外部可疑交易报告系统已受理；本接口本身不冒充外部报送连接器。 */
    @PostMapping("/{caseId}/submit")
    public SuspiciousTransactionReportView submit(@PathVariable Long caseId,
                                                  @Valid @RequestBody SubmitRequest request,
                                                  Authentication authentication) {
        SuspiciousTransactionReportView result = service.markSubmitted(
                caseId, request.expectedRevision(), request.externalReference(), authentication.getName());
        return result;
    }

    @PostMapping("/{caseId}/return")
    public SuspiciousTransactionReportView returnForCorrection(@PathVariable Long caseId,
                                                               @Valid @RequestBody ReturnRequest request,
                                                               Authentication authentication) {
        SuspiciousTransactionReportView result = service.returnForCorrection(
                caseId, request.expectedRevision(), request.reason(), authentication.getName());
        return result;
    }

    public record SubmitRequest(int expectedRevision,
                                @NotBlank @Size(min = 3, max = 128) String externalReference) { }
    public record ReturnRequest(int expectedRevision,
                                @NotBlank @Size(min = 10, max = 500) String reason) { }
}
