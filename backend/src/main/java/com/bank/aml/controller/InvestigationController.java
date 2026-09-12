package com.bank.aml.controller;

import com.bank.aml.investigation.AlertCoverageView;
import com.bank.aml.investigation.CaseInvestigationView;
import com.bank.aml.investigation.InvestigationEvidenceView;
import com.bank.aml.investigation.InvestigationHypothesisView;
import com.bank.aml.investigation.InvestigationPlaybookCatalog;
import com.bank.aml.investigation.InvestigationService;
import com.bank.aml.investigation.TransactionWindowService;
import com.bank.aml.investigation.TransactionWindowView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases/{caseId}/investigation")
public class InvestigationController {

    private final InvestigationService investigation;

    private final InvestigationPlaybookCatalog playbooks;

    private final TransactionWindowService transactionWindows;

    public InvestigationController(InvestigationService investigation, InvestigationPlaybookCatalog playbooks,
            TransactionWindowService transactionWindows) {
        this.investigation = investigation;
        this.playbooks = playbooks;
        this.transactionWindows = transactionWindows;
    }

    @GetMapping
    public CaseInvestigationView get(@PathVariable Long caseId) {
        return investigation.get(caseId);
    }

    @GetMapping("/playbooks")
    public List<InvestigationPlaybookCatalog.Playbook> playbooks(@PathVariable Long caseId) {
        investigation.get(caseId);
        return playbooks.all();
    }

    @GetMapping("/transaction-windows")
    public TransactionWindowView transactionWindows(@PathVariable Long caseId) {
        return transactionWindows.windows(caseId);
    }

    @PostMapping("/hypotheses/{hypothesisId}/evidence")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public InvestigationEvidenceView addEvidence(@PathVariable Long caseId, @PathVariable Long hypothesisId,
            @Valid @RequestBody AddEvidenceRequest request, Authentication authentication) {
        return investigation.addEvidence(caseId, hypothesisId, request.evidenceType(), request.evidenceReference(),
                request.stance(), request.findingSummary(), authentication.getName());
    }

    @PutMapping("/hypotheses/{hypothesisId}")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public InvestigationHypothesisView updateHypothesis(@PathVariable Long caseId, @PathVariable Long hypothesisId,
            @Valid @RequestBody UpdateHypothesisRequest request, Authentication authentication) {
        return investigation.updateHypothesis(caseId, hypothesisId, request.expectedRevision(), request.status(),
                request.rationale(), authentication.getName());
    }

    @PutMapping("/alerts/{alertId}/coverage")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public AlertCoverageView updateCoverage(@PathVariable Long caseId, @PathVariable Long alertId,
            @Valid @RequestBody UpdateCoverageRequest request, Authentication authentication) {
        return investigation.updateCoverage(caseId, alertId, request.expectedRevision(), request.hypothesisId(),
                request.expectedHypothesisRevision(), request.conclusion(), request.analysisSummary(),
                authentication.getName());
    }

    public record AddEvidenceRequest(
            @NotBlank @Pattern(regexp = "TRANSACTION|CUSTOMER_PROFILE|BENEFICIAL_OWNERSHIP|SANCTIONS_SCREENING|"
                    + "DOCUMENT|EXTERNAL_DATA|LEGAL") String evidenceType,
            @NotBlank @Size(min = 3, max = 160) String evidenceReference,
            @NotBlank @Pattern(regexp = "SUPPORTS|CONTRADICTS") String stance,
            @NotBlank @Size(min = 10, max = 1000) String findingSummary) {
    }

    public record UpdateHypothesisRequest(@PositiveOrZero int expectedRevision,
            @NotBlank @Pattern(regexp = "OPEN|CONFIRMED|REJECTED") String status,
            @NotBlank @Size(min = 10, max = 2000) String rationale) {
    }

    public record UpdateCoverageRequest(@PositiveOrZero int expectedRevision, @NotNull Long hypothesisId,
            /** 覆盖所关联假设的当前版本；缺失或过期都会得到明确错误，不会默认采用当前版本。 */
            @NotNull @PositiveOrZero Long expectedHypothesisRevision,
            @NotBlank @Pattern(regexp = "PENDING|SUSPICIOUS|EXPLAINED") String conclusion,
            @NotBlank @Size(min = 10, max = 1000) String analysisSummary) {
    }

}
