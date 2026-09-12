package com.bank.aml.controller;

import com.bank.aml.refund.RefundAuthorityService;
import com.bank.aml.refund.RefundLedgerService;
import com.bank.aml.security.PromptInjectionGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退款事件接口（v4 计划 §7.1 / G3-2）。 统一错误契约：REFUND_UNALLOCATED / REFUND_OVER_ALLOCATED /
 * RECIPIENT_AUTHORITY_UNRESOLVED / VERIFICATION_SUPERSEDED / OBLIGATION_UNCOVERED /
 * BASIS_CONFLICT（由各服务抛出，全局异常映射）。 手工录入不能自标银行已核实（来源状态由服务端按 sourceSystem 判定）。
 */
@RestController
@RequestMapping("/api/cases/{caseId}/refund-events")
@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
public class RefundController {

    private final RefundLedgerService ledgerService;

    private final RefundAuthorityService authorityService;

    private final PromptInjectionGuard injectionGuard;

    public RefundController(RefundLedgerService ledgerService, RefundAuthorityService authorityService,
            PromptInjectionGuard injectionGuard) {
        this.ledgerService = ledgerService;
        this.authorityService = authorityService;
        this.injectionGuard = injectionGuard;
    }

    private String operator() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    /** 登记退款事件（来源引用或手工声明；同键同内容幂等、同键不同内容 409）。 */
    @PostMapping
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public RefundLedgerService.RegistrationResult register(@PathVariable Long caseId,
            @Valid @RequestBody RefundRequest request) {
        return ledgerService.registerManual(caseId,
                new RefundLedgerService.RefundRegistration(request.sourceSystem(), request.externalEventId(),
                        request.eventStatus(), request.payerSubject(), request.payeeSubject(),
                        request.payeeAccountRef(), request.amount(), request.currency(),
                        LocalDateTime.ofInstant(request.effectiveAt(), ZoneOffset.UTC),
                        request.allocations() == null ? List.of() : request.allocations()
                            .stream()
                            .map(item -> new RefundLedgerService.AllocationInput(item.originalTransactionId(),
                                    item.originalAllocationKey(), item.allocatedAmount(), item.returnedObligationRef()))
                            .toList()),
                operator());
    }

    /** 金额账（RF-06/12）：原付/已退/待退/保留/超额检测。 */
    @GetMapping("/ledger")
    public RefundLedgerService.RefundLedger ledger(@PathVariable Long caseId,
            @RequestParam(required = false) List<String> originalTransactionIds) {
        return originalTransactionIds == null || originalTransactionIds.isEmpty() ? ledgerService.ledger(caseId)
                : ledgerService.ledgerForTransactions(caseId, Set.copyOf(originalTransactionIds));
    }

    /** 退款解释门禁评估（RF-06~10：只读，供页面在提交前展示阻断）。 */
    @PostMapping("/authority-assessment")
    public RefundAuthorityService.RefundAdmissibility assessAuthority(@PathVariable Long caseId,
            @Valid @RequestBody AuthorityRequest request) {
        return authorityService.assess(request.recipientAuthority(), request.commercialReason());
    }

    /** 冲正（RF-17）：原事件 REVERSED，余额恢复，历史保留。 */
    @PostMapping("/reversals")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public ReversalResponse reverse(@PathVariable Long caseId, @Valid @RequestBody ReversalRequest request) {
        Long reversalId = ledgerService.reverse(caseId, request.sourceSystem(), request.originalExternalEventId(),
                request.reversalExternalEventId(), operator());
        return new ReversalResponse(reversalId);
    }

    public record RefundRequest(@NotBlank @Size(max = 64) String sourceSystem,
            @NotBlank @Size(max = 128) String externalEventId,
            @Pattern(regexp = "REQUESTED|POSTED", message = "状态必须为 REQUESTED 或 POSTED") String eventStatus,
            @NotBlank @Size(max = 128) String payerSubject, @NotBlank @Size(max = 128) String payeeSubject,
            @Size(max = 128) String payeeAccountRef, @NotNull @Positive BigDecimal amount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency, @NotNull Instant effectiveAt,
            @Size(max = 500) List<@Valid AllocationRequest> allocations) {
    }

    public record AllocationRequest(@Size(max = 128) String originalTransactionId,
            @Size(max = 128) String originalAllocationKey, @NotNull @Positive BigDecimal allocatedAmount,
            @Size(max = 128) String returnedObligationRef) {
    }

    public record AuthorityRequest(@NotNull @Valid RefundAuthorityService.RecipientAuthority recipientAuthority,
            @NotNull @Valid RefundAuthorityService.CommercialReason commercialReason) {
    }

    public record ReversalRequest(@NotBlank @Size(max = 64) String sourceSystem,
            @NotBlank @Size(max = 128) String originalExternalEventId,
            @NotBlank @Size(max = 128) String reversalExternalEventId) {
    }

    public record ReversalResponse(Long reversalEventId) {
    }

}
