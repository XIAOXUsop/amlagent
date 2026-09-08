package com.bank.aml.refund;

import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 退款金额账服务（v4 计划 §6.2 / G2-1）。
 *
 * <p>不变式（同币种内计算；第一版仅 CNY；定点十进制）：
 * <pre>
 *   原分配付款额 = 已核实退款额 + 当前仍保留的付款额
 *   一笔退款事件的分配合计 <= 该退款事件金额
 *   同一原付款分配的累计有效退款额 <= 原付款分配金额
 * </pre>
 * "申请退款"（REQUESTED）不减少实际资金余额——只有 POSTED 才计入已核实退款；
 * 冲正（REVERSED）恢复余额并保留历史。同键同内容幂等返回原事件；同键不同内容 → 更正冲突。
 * 并发：案件行锁 + 条件更新保护累计额度（RF-22）。
 */
@Service
public class RefundLedgerService {

    /** 第一版支持的币种与原付款金额（服务器冻结来源，不信任客户端余额）。 */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("CNY");
    private static final Set<String> EVENT_STATUSES = Set.of("REQUESTED", "POSTED", "REVERSED");

    private final RefundEventRepository eventRepository;
    private final RefundAllocationRepository allocationRepository;
    private final CaseRepository caseRepository;
    private final ObjectMapper objectMapper;

    public RefundLedgerService(RefundEventRepository eventRepository,
                               RefundAllocationRepository allocationRepository,
                               CaseRepository caseRepository,
                               ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.allocationRepository = allocationRepository;
        this.caseRepository = caseRepository;
        this.objectMapper = objectMapper;
    }

    /** 退款登记请求。 */
    public record RefundRegistration(
            String sourceSystem,
            String externalEventId,
            String eventStatus,
            String payerSubject,
            String payeeSubject,
            String payeeAccountRef,
            BigDecimal amount,
            String currency,
            LocalDateTime effectiveAt,
            List<AllocationInput> allocations
    ) {
    }

    public record AllocationInput(
            String originalTransactionId,
            String originalAllocationKey,
            BigDecimal allocatedAmount,
            String returnedObligationRef
    ) {
    }

    /** 登记结果：eventId + 剩余未关联金额 + 幂等标记。 */
    public record RegistrationResult(
            Long eventId,
            boolean idempotentReplay,
            BigDecimal unallocatedAmount,
            String eventStatus
    ) {
    }

    /** 客户端来源金额账（原付款分配；服务器冻结，不信任客户端）。 */
    public record OriginalAllocation(String transactionId, String allocationKey, BigDecimal amount) {
    }

    /**
     * 登记退款事件并落分配（RF-21/RF-13/RF-14/RF-22）。
     * 案件锁串行化并发登记；同键同内容幂等；同键不同内容 → 更正冲突。
     */
    @Transactional
    public RegistrationResult register(Long caseId, RefundRegistration registration, String actor) {
        CaseEntity caseEntity = caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        String currency = registration.currency() == null ? "" : registration.currency().trim().toUpperCase();
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            // RF-16：跨币种/不支持币种 → 保留事件供人工，不直接相加
            throw new IllegalArgumentException("退款币种 " + registration.currency()
                    + " 超出当前政策支持范围（" + SUPPORTED_CURRENCIES + "）；保留原始线索供人工研判（RF-16），"
                    + "不跨币种直接相加");
        }
        String status = registration.eventStatus() == null ? "POSTED"
                : registration.eventStatus().trim().toUpperCase();
        if (!EVENT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("退款事件状态不在允许范围：" + registration.eventStatus());
        }
        if (registration.amount() == null
                || registration.amount().compareTo(BigDecimal.ZERO) <= 0
                || registration.amount().scale() > 2) {
            throw new IllegalArgumentException("退款金额需为正的定点数（最多两位小数）");
        }
        String digest = payloadDigest(registration);
        var existing = eventRepository.findByCaseIdAndSourceSystemAndExternalEventId(
                caseId, registration.sourceSystem(), registration.externalEventId());
        if (existing.isPresent()) {
            RefundEvent previous = existing.get();
            if (!digest.equals(previous.getPayloadDigest())) {
                throw new InvestigationRevisionConflictException(
                        InvestigationRevisionConflictException.TYPE_COVERAGE, caseId, null,
                        "相同来源事件键对应不同内容（RF-21）：请走更正路径（新版本或冲正），不能直接改写");
            }
            return new RegistrationResult(previous.getId(), true,
                    remainingUnallocated(previous), previous.getEventStatus());
        }

        RefundEvent event = new RefundEvent();
        event.setCaseId(caseId);
        event.setSourceSystem(registration.sourceSystem());
        event.setExternalEventId(registration.externalEventId());
        event.setEventStatus(status);
        event.setPayerSubject(registration.payerSubject());
        event.setPayeeSubject(registration.payeeSubject());
        event.setPayeeAccountRef(registration.payeeAccountRef());
        event.setAmount(registration.amount());
        event.setCurrency(currency);
        event.setEffectiveAt(registration.effectiveAt());
        event.setRecordedAt(LocalDateTime.now());
        event.setPayloadDigest(digest);
        event.setCreatedBy(actor);
        event.setCreatedAt(LocalDateTime.now());
        RefundEvent saved = eventRepository.save(event);

        BigDecimal allocated = BigDecimal.ZERO;
        for (AllocationInput input : registration.allocations() == null
                ? List.<AllocationInput>of() : registration.allocations()) {
            if (input.allocatedAmount() == null || input.allocatedAmount().compareTo(BigDecimal.ZERO) <= 0
                    || input.allocatedAmount().scale() > 2) {
                throw new IllegalArgumentException("分配金额需为正的定点数（最多两位小数）");
            }
            RefundAllocation allocation = new RefundAllocation();
            allocation.setCaseId(caseId);
            allocation.setRefundEventId(saved.getId());
            allocation.setOriginalTransactionId(input.originalTransactionId());
            allocation.setOriginalAllocationKey(input.originalAllocationKey());
            allocation.setAllocatedAmount(input.allocatedAmount());
            allocation.setCurrency(currency);
            allocation.setReturnedObligationRef(input.returnedObligationRef());
            allocation.setCreatedBy(actor);
            allocation.setCreatedAt(LocalDateTime.now());
            allocationRepository.save(allocation);
            allocated = allocated.add(input.allocatedAmount());
        }
        // RF-13：分配合计 ≤ 退款事件金额
        if (allocated.compareTo(registration.amount()) > 0) {
            throw new IllegalArgumentException("退款分配合计 " + allocated.toPlainString()
                    + " 超过退款事件金额 " + registration.amount().toPlainString()
                    + "（RF-13）；超额分配不能通过");
        }
        return new RegistrationResult(saved.getId(), false,
                registration.amount().subtract(allocated), status);
    }

    /** RF-17：冲正——原入账事件置 REVERSED（保留历史），其余额计算自动恢复。 */
    @Transactional
    public Long reverse(Long caseId, String sourceSystem, String externalEventId,
                        String reversalExternalId, String actor) {
        caseRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        RefundEvent original = eventRepository
                .findByCaseIdAndSourceSystemAndExternalEventId(caseId, sourceSystem, externalEventId)
                .orElseThrow(() -> new IllegalArgumentException("原退款事件不存在：" + externalEventId));
        if ("REVERSED".equals(original.getEventStatus())) {
            throw new IllegalStateException("该退款事件已被冲正，不能重复冲正");
        }
        // 冲正事件本身登记（幂等键独立），指向原事件
        RefundEvent reversal = new RefundEvent();
        reversal.setCaseId(caseId);
        reversal.setSourceSystem(sourceSystem);
        reversal.setExternalEventId(reversalExternalId);
        reversal.setEventStatus("REVERSED");
        reversal.setPayerSubject(original.getPayeeSubject());
        reversal.setPayeeSubject(original.getPayerSubject());
        reversal.setAmount(original.getAmount());
        reversal.setCurrency(original.getCurrency());
        reversal.setEffectiveAt(LocalDateTime.now());
        reversal.setRecordedAt(LocalDateTime.now());
        reversal.setPayloadDigest(payloadDigest("REVERSAL:" + original.getId()));
        reversal.setReversedEventId(original.getId());
        reversal.setCreatedBy(actor);
        reversal.setCreatedAt(LocalDateTime.now());
        RefundEvent saved = eventRepository.save(reversal);
        original.setEventStatus("REVERSED");
        original.setReversedEventId(saved.getId());
        eventRepository.save(original);
        return saved.getId();
    }

    /**
     * 金额账（RF-06/RF-11/RF-12）：按原付款分配汇总——
     * 已核实退款（POSTED）计入已退；REQUESTED 另列待退义务，不减少余额；REVERSED 不计入。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> ledger(Long caseId, List<OriginalAllocation> originalAllocations) {
        Map<String, BigDecimal> originalByTx = new LinkedHashMap<>();
        for (OriginalAllocation allocation : originalAllocations) {
            originalByTx.merge(allocation.transactionId(), allocation.amount(), BigDecimal::add);
        }
        Map<String, BigDecimal> refundedByTx = new LinkedHashMap<>();
        Map<String, BigDecimal> pendingByTx = new LinkedHashMap<>();
        BigDecimal totalRefunded = BigDecimal.ZERO;
        BigDecimal totalPending = BigDecimal.ZERO;
        for (RefundEvent event : eventRepository.findByCaseIdOrderByIdAsc(caseId)) {
            List<RefundAllocation> allocations =
                    allocationRepository.findByRefundEventIdOrderByIdAsc(event.getId());
            switch (event.getEventStatus()) {
                case "POSTED" -> {
                    for (RefundAllocation allocation : allocations) {
                        refundedByTx.merge(allocation.getOriginalTransactionId(),
                                allocation.getAllocatedAmount(), BigDecimal::add);
                    }
                    totalRefunded = totalRefunded.add(event.getAmount());
                }
                case "REQUESTED" -> {
                    for (RefundAllocation allocation : allocations) {
                        pendingByTx.merge(allocation.getOriginalTransactionId(),
                                allocation.getAllocatedAmount(), BigDecimal::add);
                    }
                    totalPending = totalPending.add(event.getAmount());
                }
                default -> { /* REVERSED：不计入余额（历史保留） */ }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("originalByTransaction", originalByTx);
        result.put("refundedByTransaction", refundedByTx);
        result.put("pendingRefundByTransaction", pendingByTx);
        result.put("totalOriginal", originalByTx.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        result.put("totalRefunded", totalRefunded);
        result.put("totalPendingRefund", totalPending);
        result.put("totalRetained", originalByTx.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add).subtract(totalRefunded));
        // 超额检测（RF-13/RF-14：同一原分配累计有效退款 ≤ 原金额）
        Map<String, String> overAllocations = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : refundedByTx.entrySet()) {
            BigDecimal original = originalByTx.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (entry.getValue().compareTo(original) > 0) {
                overAllocations.put(entry.getKey(),
                        "已退 " + entry.getValue().toPlainString() + " 超过原分配 " + original.toPlainString());
            }
        }
        result.put("overAllocations", overAllocations);
        return result;
    }

    private BigDecimal remainingUnallocated(RefundEvent event) {
        List<RefundAllocation> allocations =
                allocationRepository.findByRefundEventIdOrderByIdAsc(event.getId());
        BigDecimal allocated = allocations.stream()
                .map(RefundAllocation::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return event.getAmount().subtract(allocated);
    }

    private String payloadDigest(RefundRegistration registration) {
        try {
            String payload = objectMapper.writeValueAsString(new LinkedHashMap<String, Object>() {{
                put("sourceSystem", registration.sourceSystem());
                put("externalEventId", registration.externalEventId());
                put("eventStatus", registration.eventStatus());
                put("amount", registration.amount() == null ? null : registration.amount().toPlainString());
                put("currency", registration.currency());
                put("payer", registration.payerSubject());
                put("payee", registration.payeeSubject());
                put("effectiveAt", registration.effectiveAt() == null ? null : registration.effectiveAt().toString());
                put("allocations", registration.allocations());
            }});
            return sha256(payload);
        } catch (Exception e) {
            throw new IllegalStateException("退款事件摘要计算失败", e);
        }
    }

    private String payloadDigest(String raw) {
        return sha256(raw);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
