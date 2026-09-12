package com.bank.aml.refund;

import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.TransactionRecord;
import com.bank.aml.explanation.ExplanationFactInvalidationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款金额账服务（v4 计划 §6.2 / G2-1）。
 *
 * <p>
 * 不变式（同币种内计算；第一版仅 CNY；定点十进制）： <pre>
 *   原分配付款额 = 已核实退款额 + 当前仍保留的付款额
 *   一笔退款事件的分配合计 <= 该退款事件金额
 *   同一原付款分配的累计有效退款额 <= 原付款分配金额
 * </pre> "申请退款"（REQUESTED）不减少实际资金余额——只有 POSTED 才计入已核实退款；
 * 冲正（REVERSED）恢复余额并保留历史。同键同内容幂等返回原事件；同键不同内容 → 更正冲突。 并发：案件行锁 + 条件更新保护累计额度（RF-22）。
 */
@Service
public class RefundLedgerService {

    /** 第一版支持的币种与原付款金额（服务器冻结来源，不信任客户端余额）。 */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("CNY");

    private static final String LEDGER_CURRENCY = "CNY";

    private static final Set<String> EVENT_STATUSES = Set.of("REQUESTED", "POSTED", "REVERSED");

    private static final Set<String> VERIFIED_POSTED_SOURCES = Set.of("CORE_BANKING", "PAYMENT_CORE");

    private final RefundEventRepository eventRepository;

    private final RefundAllocationRepository allocationRepository;

    private final CaseRepository caseRepository;

    private final ObjectMapper objectMapper;

    private final CustomerDataPort customerDataPort;

    private final ExplanationFactInvalidationService factInvalidationService;

    private final Clock clock;

    @Autowired
    public RefundLedgerService(RefundEventRepository eventRepository, RefundAllocationRepository allocationRepository,
            CaseRepository caseRepository, ObjectMapper objectMapper, CustomerDataPort customerDataPort,
            ExplanationFactInvalidationService factInvalidationService, Clock clock) {
        this.eventRepository = eventRepository;
        this.allocationRepository = allocationRepository;
        this.caseRepository = caseRepository;
        this.objectMapper = objectMapper;
        this.customerDataPort = customerDataPort;
        this.factInvalidationService = factInvalidationService;
        this.clock = clock;
    }

    /** 退款登记请求。 */
    public record RefundRegistration(String sourceSystem, String externalEventId, String eventStatus,
            String payerSubject, String payeeSubject, String payeeAccountRef, BigDecimal amount, String currency,
            LocalDateTime effectiveAt, List<AllocationInput> allocations) {
    }

    public record AllocationInput(String originalTransactionId, String originalAllocationKey,
            BigDecimal allocatedAmount, String returnedObligationRef) {
    }

    /** 登记结果：eventId + 剩余未关联金额 + 幂等标记。 */
    public record RegistrationResult(Long eventId, boolean idempotentReplay, BigDecimal unallocatedAmount,
            String currency, String eventStatus) {
    }

    /** 客户端来源金额账（原付款分配；服务器冻结，不信任客户端）。 */
    public record OriginalAllocation(String transactionId, String allocationKey, BigDecimal amount) {
    }

    /**
     * 登记退款事件并落分配（RF-21/RF-13/RF-14/RF-22）。 案件锁串行化并发登记；同键同内容幂等；同键不同内容 → 更正冲突。
     */
    @Transactional
    public RegistrationResult register(Long caseId, RefundRegistration registration, String actor) {
        CaseEntity caseEntity = caseRepository.findByIdForUpdate(caseId)
            .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        String currency = registration.currency() == null ? "" : registration.currency().trim().toUpperCase();
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            // RF-16：跨币种/不支持币种 → 保留事件供人工，不直接相加
            throw new IllegalArgumentException("退款币种 " + registration.currency() + " 超出当前政策支持范围（" + SUPPORTED_CURRENCIES
                    + "）；保留原始线索供人工研判（RF-16），" + "不跨币种直接相加");
        }
        String sourceSystem = normalizeUpper(registration.sourceSystem());
        String externalEventId = normalize(registration.externalEventId());
        if (sourceSystem.isBlank() || externalEventId.isBlank()) {
            throw new IllegalArgumentException("退款来源系统与来源事件编号不能为空");
        }
        String status = registration.eventStatus() == null ? "REQUESTED"
                : registration.eventStatus().trim().toUpperCase();
        if (!EVENT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("退款事件状态不在允许范围：" + registration.eventStatus());
        }
        if ("REVERSED".equals(status)) {
            throw new IllegalArgumentException("冲正状态只能通过专用冲正入口登记");
        }
        if ("POSTED".equals(status) && !VERIFIED_POSTED_SOURCES.contains(sourceSystem)) {
            throw new IllegalArgumentException("来源 " + sourceSystem + " 未经资金系统核实，不能登记为已入账退款；请先登记 REQUESTED 并完成核验");
        }
        if (registration.amount() == null || registration.amount().compareTo(BigDecimal.ZERO) <= 0
                || registration.amount().scale() > 2) {
            throw new IllegalArgumentException("退款金额需为正的定点数（最多两位小数）");
        }
        RefundRegistration normalizedRegistration = new RefundRegistration(sourceSystem, externalEventId, status,
                normalize(registration.payerSubject()), normalize(registration.payeeSubject()),
                normalize(registration.payeeAccountRef()), registration.amount(), currency, registration.effectiveAt(),
                registration.allocations());
        String digest = payloadDigest(normalizedRegistration);
        var existing = eventRepository.findByCaseIdAndSourceSystemAndExternalEventId(caseId, sourceSystem,
                externalEventId);
        if (existing.isPresent()) {
            RefundEvent previous = existing.get();
            if (!digest.equals(previous.getPayloadDigest())) {
                throw new InvestigationRevisionConflictException(InvestigationRevisionConflictException.TYPE_COVERAGE,
                        caseId, null, "相同来源事件键对应不同内容（RF-21）：请走更正路径（新版本或冲正），不能直接改写");
            }
            return new RegistrationResult(previous.getId(), true, remainingUnallocated(previous),
                    previous.getCurrency(), previous.getEventStatus());
        }

        List<AllocationInput> requestedAllocations = registration.allocations() == null ? List.of()
                : registration.allocations();
        BigDecimal allocated = BigDecimal.ZERO;
        Map<String, BigDecimal> newByTransaction = new LinkedHashMap<>();
        Map<String, OriginalTransaction> authoritativeTransactions = authoritativeTransactions(caseEntity);
        Map<String, BigDecimal> authoritativeAmounts = new LinkedHashMap<>();
        authoritativeTransactions
            .forEach((transactionId, transaction) -> authoritativeAmounts.put(transactionId, transaction.amount()));
        for (AllocationInput input : requestedAllocations) {
            if (input.allocatedAmount() == null || input.allocatedAmount().compareTo(BigDecimal.ZERO) <= 0
                    || input.allocatedAmount().scale() > 2) {
                throw new IllegalArgumentException("分配金额需为正的定点数（最多两位小数）");
            }
            String transactionId = normalize(input.originalTransactionId());
            if (transactionId.isBlank() || !authoritativeTransactions.containsKey(transactionId)) {
                throw new IllegalArgumentException("退款关联的原付款 " + input.originalTransactionId() + " 不存在于案件客户的权威交易来源中");
            }
            OriginalTransaction original = authoritativeTransactions.get(transactionId);
            if (!currency.equals(original.currency())) {
                throw new IllegalArgumentException("退款币种与原付款币种不一致，禁止跨币种分配");
            }
            allocated = allocated.add(input.allocatedAmount());
            newByTransaction.merge(transactionId, input.allocatedAmount(), BigDecimal::add);
        }
        if (allocated.compareTo(registration.amount()) > 0) {
            throw new IllegalArgumentException("退款分配合计 " + allocated.toPlainString() + " 超过退款事件金额 "
                    + registration.amount().toPlainString() + "（RF-13）；超额分配不能通过");
        }
        enforceCumulativeLimits(caseId, newByTransaction, authoritativeAmounts);

        RefundEvent event = new RefundEvent();
        event.setCaseId(caseId);
        event.setSourceSystem(sourceSystem);
        event.setExternalEventId(externalEventId);
        event.setEventStatus(status);
        event.setPayerSubject(normalizedRegistration.payerSubject());
        event.setPayeeSubject(normalizedRegistration.payeeSubject());
        event.setPayeeAccountRef(normalizedRegistration.payeeAccountRef());
        event.setAmount(registration.amount());
        event.setCurrency(currency);
        event.setEffectiveAt(registration.effectiveAt());
        event.setRecordedAt(LocalDateTime.now(clock));
        event.setPayloadDigest(digest);
        event.setCreatedBy(actor);
        event.setCreatedAt(LocalDateTime.now(clock));
        RefundEvent saved = eventRepository.save(event);

        for (AllocationInput input : requestedAllocations) {
            RefundAllocation allocation = new RefundAllocation();
            allocation.setCaseId(caseId);
            allocation.setRefundEventId(saved.getId());
            allocation.setOriginalTransactionId(normalize(input.originalTransactionId()));
            allocation.setOriginalAllocationKey(input.originalAllocationKey());
            allocation.setAllocatedAmount(input.allocatedAmount());
            allocation.setCurrency(currency);
            allocation.setReturnedObligationRef(input.returnedObligationRef());
            allocation.setCreatedBy(actor);
            allocation.setCreatedAt(LocalDateTime.now(clock));
            allocationRepository.save(allocation);
        }
        factInvalidationService.invalidate(caseId, "新增退款事实：" + externalEventId, actor);
        return new RegistrationResult(saved.getId(), false, registration.amount().subtract(allocated), currency,
                status);
    }

    /** 分析员/客户声明入口永远不能授予资金已入账状态。 */
    @Transactional
    public RegistrationResult registerManual(Long caseId, RefundRegistration registration, String actor) {
        String requestedStatus = normalizeUpper(registration.eventStatus());
        if (!requestedStatus.isBlank() && !"REQUESTED".equals(requestedStatus)) {
            throw new IllegalArgumentException("手工声明只能登记为 REQUESTED；POSTED 必须来自受信资金系统");
        }
        return register(caseId, new RefundRegistration("CUSTOMER_PROVIDED", registration.externalEventId(), "REQUESTED",
                registration.payerSubject(), registration.payeeSubject(), registration.payeeAccountRef(),
                registration.amount(), registration.currency(), registration.effectiveAt(), registration.allocations()),
                actor);
    }

    /** RF-17：冲正——原入账事件置 REVERSED（保留历史），其余额计算自动恢复。 */
    @Transactional
    public Long reverse(Long caseId, String sourceSystem, String externalEventId, String reversalExternalId,
            String actor) {
        caseRepository.findByIdForUpdate(caseId).orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
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
        reversal.setEffectiveAt(LocalDateTime.now(clock));
        reversal.setRecordedAt(LocalDateTime.now(clock));
        reversal.setPayloadDigest(payloadDigest("REVERSAL:" + original.getId()));
        reversal.setReversedEventId(original.getId());
        reversal.setCreatedBy(actor);
        reversal.setCreatedAt(LocalDateTime.now(clock));
        RefundEvent saved = eventRepository.save(reversal);
        original.setEventStatus("REVERSED");
        original.setReversedEventId(saved.getId());
        eventRepository.save(original);
        factInvalidationService.invalidate(caseId, "退款冲正事实：" + externalEventId, actor);
        return saved.getId();
    }

    /**
     * 金额账（RF-06/RF-11/RF-12）：按原付款分配汇总—— 已核实退款（POSTED）计入已退；REQUESTED 另列待退义务，不减少余额；REVERSED
     * 不计入。
     */
    @Transactional(readOnly = true)
    public RefundLedger ledger(Long caseId) {
        return ledgerForTransactions(caseId, null);
    }

    /** 按客户端提供的交易身份筛选展示，但金额仍只取服务端权威来源。 */
    @Transactional(readOnly = true)
    public RefundLedger ledgerForTransactions(Long caseId, Set<String> transactionIds) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        Map<String, BigDecimal> originalByTx = new LinkedHashMap<>();
        authoritativeTransactions(caseEntity).forEach((transactionId, transaction) -> {
            if (LEDGER_CURRENCY.equals(transaction.currency())) {
                originalByTx.put(transactionId, transaction.amount());
            }
        });
        if (transactionIds != null && !transactionIds.isEmpty()) {
            originalByTx.entrySet().removeIf(entry -> !transactionIds.contains(entry.getKey()));
        }
        Map<String, BigDecimal> refundedByTx = new LinkedHashMap<>();
        Map<String, BigDecimal> pendingByTx = new LinkedHashMap<>();
        BigDecimal totalRefunded = BigDecimal.ZERO;
        BigDecimal totalPending = BigDecimal.ZERO;
        for (RefundEvent event : eventRepository.findByCaseIdOrderByIdAsc(caseId)) {
            List<RefundAllocation> allocations = allocationRepository.findByRefundEventIdOrderByIdAsc(event.getId());
            switch (event.getEventStatus()) {
                case "POSTED" -> {
                    for (RefundAllocation allocation : allocations) {
                        if (!originalByTx.containsKey(allocation.getOriginalTransactionId())) {
                            continue;
                        }
                        requireLedgerCurrency(event, allocation);
                        refundedByTx.merge(allocation.getOriginalTransactionId(), allocation.getAllocatedAmount(),
                                BigDecimal::add);
                        totalRefunded = totalRefunded.add(allocation.getAllocatedAmount());
                    }
                }
                case "REQUESTED" -> {
                    for (RefundAllocation allocation : allocations) {
                        if (!originalByTx.containsKey(allocation.getOriginalTransactionId())) {
                            continue;
                        }
                        requireLedgerCurrency(event, allocation);
                        pendingByTx.merge(allocation.getOriginalTransactionId(), allocation.getAllocatedAmount(),
                                BigDecimal::add);
                        totalPending = totalPending.add(allocation.getAllocatedAmount());
                    }
                }
                default -> {
                    /* REVERSED：不计入余额（历史保留） */ }
            }
        }
        BigDecimal totalOriginal = originalByTx.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRetained = totalOriginal.subtract(totalRefunded);
        // 超额检测（RF-13/RF-14：同一原分配累计有效退款 ≤ 原金额）
        Map<String, String> overAllocations = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : refundedByTx.entrySet()) {
            BigDecimal original = originalByTx.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            if (entry.getValue().compareTo(original) > 0) {
                overAllocations.put(entry.getKey(),
                        "已退 " + entry.getValue().toPlainString() + " 超过原分配 " + original.toPlainString());
            }
        }
        return new RefundLedger(Map.copyOf(originalByTx), Map.copyOf(refundedByTx), Map.copyOf(pendingByTx),
                totalOriginal, totalRefunded, totalPending, totalRetained, LEDGER_CURRENCY,
                Map.copyOf(overAllocations));
    }

    /** 兼容旧调用签名；客户端传入的原付款金额被明确忽略。 */
    @Deprecated
    public RefundLedger ledger(Long caseId, List<OriginalAllocation> ignoredClientAllocations) {
        Set<String> requestedIds = new LinkedHashSet<>();
        for (OriginalAllocation allocation : ignoredClientAllocations == null ? List.<OriginalAllocation>of()
                : ignoredClientAllocations) {
            if (allocation.transactionId() != null && !allocation.transactionId().isBlank()) {
                requestedIds.add(allocation.transactionId().trim());
            }
        }
        return ledgerForTransactions(caseId, requestedIds);
    }

    public record RefundLedger(Map<String, BigDecimal> originalByTransaction,
            Map<String, BigDecimal> refundedByTransaction, Map<String, BigDecimal> pendingRefundByTransaction,
            BigDecimal totalOriginal, BigDecimal totalRefunded, BigDecimal totalPendingRefund, BigDecimal totalRetained,
            String currency, Map<String, String> overAllocations) {

        /** 仅供旧测试和内部迁移期调用；HTTP 层始终按固定 record 字段序列化。 */
        @Deprecated
        public Object get(String field) {
            return switch (field) {
                case "originalByTransaction" -> originalByTransaction;
                case "refundedByTransaction" -> refundedByTransaction;
                case "pendingRefundByTransaction" -> pendingRefundByTransaction;
                case "totalOriginal" -> totalOriginal;
                case "totalRefunded" -> totalRefunded;
                case "totalPendingRefund" -> totalPendingRefund;
                case "totalRetained" -> totalRetained;
                case "currency" -> currency;
                case "overAllocations" -> overAllocations;
                default -> null;
            };
        }
    }

    private void enforceCumulativeLimits(Long caseId, Map<String, BigDecimal> newByTransaction,
            Map<String, BigDecimal> authoritativeAmounts) {
        Map<Long, RefundEvent> eventsById = new LinkedHashMap<>();
        for (RefundEvent event : eventRepository.findByCaseIdOrderByIdAsc(caseId)) {
            eventsById.put(event.getId(), event);
        }
        for (Map.Entry<String, BigDecimal> entry : newByTransaction.entrySet()) {
            BigDecimal existing = allocationRepository
                .findByCaseIdAndOriginalTransactionIdOrderByIdAsc(caseId, entry.getKey())
                .stream()
                .filter(allocation -> {
                    RefundEvent event = eventsById.get(allocation.getRefundEventId());
                    return event != null
                            && ("POSTED".equals(event.getEventStatus()) || "REQUESTED".equals(event.getEventStatus()));
                })
                .map(RefundAllocation::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal original = authoritativeAmounts.get(entry.getKey());
            if (existing.add(entry.getValue()).compareTo(original) > 0) {
                throw new IllegalArgumentException(
                        "原付款 " + entry.getKey() + " 累计有效退款 " + existing.add(entry.getValue()).toPlainString()
                                + " 超过权威原付款金额 " + original.toPlainString() + "（RF-14）");
            }
        }
    }

    private Map<String, OriginalTransaction> authoritativeTransactions(CaseEntity caseEntity) {
        Map<String, OriginalTransaction> transactions = new LinkedHashMap<>();
        for (TransactionRecord transaction : customerDataPort.transactionsOf(caseEntity.getCustomerId())) {
            if (transaction.sourceRecordId() != null && !transaction.sourceRecordId().isBlank()
                    && transaction.amount() != null && transaction.currency() != null
                    && !transaction.currency().isBlank()) {
                transactions.putIfAbsent(transaction.sourceRecordId(),
                        new OriginalTransaction(transaction.amount(), transaction.currency().trim().toUpperCase()));
            }
        }
        return transactions;
    }

    private void requireLedgerCurrency(RefundEvent event, RefundAllocation allocation) {
        if (!LEDGER_CURRENCY.equals(event.getCurrency()) || !LEDGER_CURRENCY.equals(allocation.getCurrency())) {
            throw new IllegalStateException("退款账本存在币种不一致的数据，已拒绝汇总");
        }
    }

    private record OriginalTransaction(BigDecimal amount, String currency) {
    }

    private BigDecimal remainingUnallocated(RefundEvent event) {
        List<RefundAllocation> allocations = allocationRepository.findByRefundEventIdOrderByIdAsc(event.getId());
        BigDecimal allocated = allocations.stream()
            .map(RefundAllocation::getAllocatedAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return event.getAmount().subtract(allocated);
    }

    private String payloadDigest(RefundRegistration registration) {
        try {
            String payload = objectMapper.writeValueAsString(new LinkedHashMap<String, Object>() {
                {
                    put("sourceSystem", normalizeUpper(registration.sourceSystem()));
                    put("externalEventId", normalize(registration.externalEventId()));
                    put("eventStatus", normalizeUpper(registration.eventStatus()));
                    put("amount", registration.amount() == null ? null
                            : registration.amount().stripTrailingZeros().toPlainString());
                    put("currency", normalizeUpper(registration.currency()));
                    put("payer", normalize(registration.payerSubject()));
                    put("payee", normalize(registration.payeeSubject()));
                    put("payeeAccountRef", normalize(registration.payeeAccountRef()));
                    put("effectiveAt",
                            registration.effectiveAt() == null ? null : registration.effectiveAt().toString());
                    put("allocations", registration.allocations());
                }
            });
            return sha256(payload);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("退款事件摘要计算失败", e);
        }
    }

    private String payloadDigest(String raw) {
        return sha256(raw);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeUpper(String value) {
        return normalize(value).toUpperCase(Locale.ROOT);
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
