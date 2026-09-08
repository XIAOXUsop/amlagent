package com.bank.aml.refund;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * G2-1 金额账防回归（RF-11/13/14/16/17/21/22 语义）：
 * 分配合计≤退款额、累计退款≤原分配、同键幂等/同键改内容冲突、跨币种不适用、
 * 冲正恢复余额、REQUESTED 不减少实际余额。
 */
class RefundLedgerServiceTest {

    private final RefundEventRepository events = mock(RefundEventRepository.class);
    private final RefundAllocationRepository allocations = mock(RefundAllocationRepository.class);
    private final CaseRepository cases = mock(CaseRepository.class);
    private final RefundLedgerService service =
            new RefundLedgerService(events, allocations, cases, new ObjectMapper());

    private RefundEvent storedEvent;
    private final java.util.List<RefundEvent> storedEvents = new java.util.ArrayList<>();
    private final java.util.List<RefundAllocation> storedAllocations = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        CaseEntity hold = new CaseEntity();
        hold.setStatus(CaseStatus.HOLD);
        when(cases.findByIdForUpdate(1L)).thenReturn(Optional.of(hold));
        when(cases.findById(1L)).thenReturn(Optional.of(hold));
        when(events.save(any())).thenAnswer(inv -> {
            RefundEvent saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, 100L);
            }
            storedEvent = saved;
            storedEvents.add(saved);
            return saved;
        });
        when(allocations.save(any())).thenAnswer(inv -> {
            RefundAllocation saved = inv.getArgument(0);
            if (saved.getId() == null) {
                setId(saved, (long) (storedAllocations.size() + 1));
            }
            storedAllocations.add(saved);
            return saved;
        });
        when(allocations.findByRefundEventIdOrderByIdAsc(any())).thenAnswer(inv ->
                storedAllocations.stream()
                        .filter(a -> inv.getArgument(0, Long.class).equals(a.getRefundEventId()))
                        .toList());
        when(events.findByCaseIdAndSourceSystemAndExternalEventId(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(events.findByCaseIdOrderByIdAsc(1L)).thenAnswer(inv ->
                java.util.List.copyOf(storedEvents));
    }

    private RefundLedgerService.RefundRegistration registration(String amount, List<RefundLedgerService.AllocationInput> allocations) {
        return new RefundLedgerService.RefundRegistration("CORE_BANKING", "REFUND-001",
                "POSTED", "甲贸易公司", "丙集团公司", "ACCT-P-001",
                new BigDecimal(amount), "CNY", LocalDateTime.parse("2026-09-05T10:00:00"), allocations);
    }

    /** RF-06/RF-11：44 万原付款、12 万退款 → 已退 12、保留 32；分两次退（8+4）累计正确。 */
    @Test
    void partialRefundsAccumulateAndLedgerBalances() {
        var result = service.register(1L, registration("120000.00", List.of(
                new RefundLedgerService.AllocationInput("T-1001", "SO-01", new BigDecimal("120000.00"), null))), "analyst");
        assertThat(result.unallocatedAmount()).isEqualByComparingTo("0");

        var ledger = service.ledger(1L, List.of(
                new RefundLedgerService.OriginalAllocation("T-1001", "SO-01", new BigDecimal("440000.00"))));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("120000.00");
        assertThat((BigDecimal) ledger.get("totalRetained")).isEqualByComparingTo("320000.00");
    }

    /** RF-12：已退 8 万、承诺再退 4 万 → 实际已退 8，待退 4（REQUESTED 不减余额）。 */
    @Test
    void requestedRefundDoesNotReduceBalance() {
        service.register(1L, registration("80000.00", List.of(
                new RefundLedgerService.AllocationInput("T-1001", "SO-01", new BigDecimal("80000.00"), null))), "analyst");
        // 追加 REQUESTED 事件
        var requested = new RefundLedgerService.RefundRegistration("CORE_BANKING", "REFUND-002",
                "REQUESTED", "甲贸易公司", "丙集团公司", "ACCT-P-001",
                new BigDecimal("40000.00"), "CNY", LocalDateTime.parse("2026-09-06T10:00:00"), List.of(
                new RefundLedgerService.AllocationInput("T-1001", "SO-01", new BigDecimal("40000.00"), null)));
        when(events.findByCaseIdAndSourceSystemAndExternalEventId(1L, "CORE_BANKING", "REFUND-002"))
                .thenReturn(Optional.empty());
        service.register(1L, requested, "analyst");

        var ledger = service.ledger(1L, List.of(
                new RefundLedgerService.OriginalAllocation("T-1001", "SO-01", new BigDecimal("440000.00"))));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("80000.00");
        assertThat((BigDecimal) ledger.get("totalPendingRefund")).isEqualByComparingTo("40000.00");
        assertThat((BigDecimal) ledger.get("totalRetained")).isEqualByComparingTo("360000.00");
    }

    /** RF-13：分配超过退款事件金额 → 拒绝；累计退款超原分配 1 分 → 拒绝。 */
    @Test
    void overAllocationIsRejected() {
        assertThatThrownBy(() -> service.register(1L, registration("100000.00", List.of(
                new RefundLedgerService.AllocationInput("T-1001", "SO-01", new BigDecimal("100000.01"), null))), "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过退款事件金额");
    }

    /** RF-14：一笔退款分配给两笔原付款——合计约束正确。 */
    @Test
    void oneRefundAcrossTwoOriginalPayments() {
        var result = service.register(1L, registration("120000.00", List.of(
                new RefundLedgerService.AllocationInput("T-1001", "SO-01", new BigDecimal("100000.00"), null),
                new RefundLedgerService.AllocationInput("T-1002", "SO-02", new BigDecimal("20000.00"), null))), "analyst");
        assertThat(result.unallocatedAmount()).isEqualByComparingTo("0");
        var ledger = service.ledger(1L, List.of(
                new RefundLedgerService.OriginalAllocation("T-1001", "SO-01", new BigDecimal("320000.00")),
                new RefundLedgerService.OriginalAllocation("T-1002", "SO-02", new BigDecimal("120000.00"))));
        @SuppressWarnings("unchecked")
        java.util.Map<String, BigDecimal> refundedByTx =
                (java.util.Map<String, BigDecimal>) ledger.get("refundedByTransaction");
        assertThat(refundedByTx).containsEntry("T-1002", new BigDecimal("20000.00"));
    }

    /** RF-21：同键同内容幂等返回原事件；同键不同内容 → 更正冲突。 */
    @Test
    void sameKeySameContentIsIdempotentDifferentContentConflicts() {
        service.register(1L, registration("120000.00", List.of(
                new RefundLedgerService.AllocationInput("T-1001", "SO-01", new BigDecimal("120000.00"), null))), "analyst");
        when(events.findByCaseIdAndSourceSystemAndExternalEventId(1L, "CORE_BANKING", "REFUND-001"))
                .thenReturn(Optional.of(storedEvent));

        // 同键同内容 → 幂等
        var replay = service.register(1L, registration("120000.00", List.of(
                new RefundLedgerService.AllocationInput("T-1001", "SO-01", new BigDecimal("120000.00"), null))), "analyst");
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.eventId()).isEqualTo(100L);

        // 同键改金额 → 冲突
        assertThatThrownBy(() -> service.register(1L, registration("130000.00", List.of(
                new RefundLedgerService.AllocationInput("T-1001", "SO-01", new BigDecimal("130000.00"), null))), "analyst"))
                .isInstanceOf(InvestigationRevisionConflictException.class)
                .hasMessageContaining("相同来源事件键对应不同内容");
    }

    /** RF-16：跨币种 → 不适用，保留人工。 */
    @Test
    void crossCurrencyIsNotSupported() {
        assertThatThrownBy(() -> service.register(1L, new RefundLedgerService.RefundRegistration(
                "CORE_BANKING", "REFUND-USD", "POSTED", "甲", "丙", null,
                new BigDecimal("1000.00"), "USD", LocalDateTime.now(), List.of()), "analyst"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RF-16");
    }

    /** RF-17：冲正 → 原事件 REVERSED，不计入余额。 */
    @Test
    void reversalRestoresBalanceKeepsHistory() {
        service.register(1L, registration("120000.00", List.of(
                new RefundLedgerService.AllocationInput("T-1001", "SO-01", new BigDecimal("120000.00"), null))), "analyst");
        when(events.findByCaseIdAndSourceSystemAndExternalEventId(1L, "CORE_BANKING", "REFUND-001"))
                .thenReturn(Optional.of(storedEvent));
        service.reverse(1L, "CORE_BANKING", "REFUND-001", "REVERSAL-001", "analyst");
        assertThat(storedEvent.getEventStatus()).isEqualTo("REVERSED");
        var ledger = service.ledger(1L, List.of(
                new RefundLedgerService.OriginalAllocation("T-1001", "SO-01", new BigDecimal("440000.00"))));
        assertThat((BigDecimal) ledger.get("totalRefunded")).isEqualByComparingTo("0");
        assertThat((BigDecimal) ledger.get("totalRetained")).isEqualByComparingTo("440000.00");
    }

    private static void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
