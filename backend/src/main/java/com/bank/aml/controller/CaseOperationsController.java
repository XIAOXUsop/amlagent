package com.bank.aml.controller;

import com.bank.aml.operations.CaseOperationsService;
import com.bank.aml.operations.CaseOperationsView;
import com.bank.aml.operations.CasePriority;
import com.bank.aml.operations.OperationPhase;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/case-operations")
public class CaseOperationsController {
    private final CaseOperationsService operations;

    public CaseOperationsController(CaseOperationsService operations) { this.operations = operations; }

    @GetMapping
    public List<CaseOperationsView> queue(
            @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(required = false) CasePriority priority,
            @RequestParam(required = false) OperationPhase phase,
            Authentication authentication) {
        String role = authentication.getAuthorities().stream()
                .map(value -> value.getAuthority())
                .filter(value -> value.startsWith("ROLE_"))
                .map(value -> value.substring(5)).findFirst().orElse("");
        return operations.queue(authentication.getName(), role, overdueOnly, priority, phase);
    }

    @GetMapping("/{caseId}")
    public CaseOperationsView get(@PathVariable Long caseId) { return operations.get(caseId); }
}
