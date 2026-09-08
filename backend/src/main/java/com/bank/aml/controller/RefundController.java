package com.bank.aml.controller;

import com.bank.aml.refund.RefundAuthorityService;
import com.bank.aml.refund.RefundLedgerService;
import com.bank.aml.security.PromptInjectionGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 退款事件接口（v4 计划 §7.1 / G3-2）。
 * 统一错误契约：REFUND_UNALLOCATED / REFUND_OVER_ALLOCATED / RECIPIENT_AUTHORITY_UNRESOLVED /
 * VERIFICATION_SUPERSEDED / OBLIGATION_UNCOVERED / BASIS_CONFLICT（由各服务抛出，全局异常映射）。
 * 手工录入不能自标银行已核实（来源状态由服务端按 sourceSystem 判定）。
 */
@RestController
@RequestMapping("/api/cases/{caseId}/refund-events")
@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
public class RefundController {

    private final RefundLedgerService ledgerService;
    private final RefundAuthorityService authorityService;
    private final PromptInjectionGuard injectionGuard;

    public RefundController(RefundLedgerService ledgerService,
                            RefundAuthorityService authorityService,
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
                                                           @RequestBody RefundRequest request) {
        return ledgerService.register(caseId, new RefundLedgerService.RefundRegistration(
                request.sourceSystem(), request.externalEventId(), request.eventStatus(),
                request.payerSubject(), request.payeeSubject(), request.payeeAccountRef(),
                request.amount(), request.currency(), request.effectiveAt(),
                request.allocations() == null ? List.of() : request.allocations().stream()
                        .map(item -> new RefundLedgerService.AllocationInput(
                                item.originalTransactionId(), item.originalAllocationKey(),
                                item.allocatedAmount(), item.returnedObligationRef()))
                        .toList()), operator());
    }

    /** 金额账（RF-06/12）：原付/已退/待退/保留/超额检测。 */
    @GetMapping("/ledger")
    public Map<String, Object> ledger(@PathVariable Long caseId,
                                      @org.springframework.web.bind.annotation.RequestParam
                                      List<String> originalTransactionIds,
                                      @org.springframework.web.bind.annotation.RequestParam
                                      List<BigDecimal> originalAmounts) {
        if (originalTransactionIds.size() != originalAmounts.size()) {
            throw new IllegalArgumentException("原付款交易与金额数量不一致");
        }
        List<RefundLedgerService.OriginalAllocation> allocations = new java.util.ArrayList<>();
        for (int i = 0; i < originalTransactionIds.size(); i++) {
            allocations.add(new RefundLedgerService.OriginalAllocation(
                    originalTransactionIds.get(i), "TXN", originalAmounts.get(i)));
        }
        return ledgerService.ledger(caseId, allocations);
    }

    /** 退款解释门禁评估（RF-06~10：只读，供页面在提交前展示阻断）。 */
    @PostMapping("/authority-assessment")
    public RefundAuthorityService.RefundAdmissibility assessAuthority(
            @PathVariable Long caseId, @RequestBody AuthorityRequest request) {
        return authorityService.assess(request.recipientAuthority(), request.commercialReason());
    }

    /** 冲正（RF-17）：原事件 REVERSED，余额恢复，历史保留。 */
    @PostMapping("/reversals")
    @PreAuthorize("hasAnyRole('ANALYST','ADMIN')")
    public Map<String, Object> reverse(@PathVariable Long caseId, @RequestBody ReversalRequest request) {
        Long reversalId = ledgerService.reverse(caseId, request.sourceSystem(),
                request.originalExternalEventId(), request.reversalExternalEventId(), operator());
        return Map.of("reversalEventId", reversalId);
    }

    public record RefundRequest(String sourceSystem, String externalEventId, String eventStatus,
                                String payerSubject, String payeeSubject, String payeeAccountRef,
                                BigDecimal amount, String currency, LocalDateTime effectiveAt,
                                List<AllocationRequest> allocations) {
    }

    public record AllocationRequest(String originalTransactionId, String originalAllocationKey,
                                    BigDecimal allocatedAmount, String returnedObligationRef) {
    }

    public record AuthorityRequest(RefundAuthorityService.RecipientAuthority recipientAuthority,
                                   RefundAuthorityService.CommercialReason commercialReason) {
    }

    public record ReversalRequest(String sourceSystem, String originalExternalEventId,
                                  String reversalExternalEventId) {
    }
}
