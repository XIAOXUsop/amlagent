package com.bank.aml.investigation;

import com.bank.aml.common.time.BusinessZone;
import com.bank.aml.config.RiskProperties;
import com.bank.aml.datasource.CustomerDataPort;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.domain.TransactionRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以客户为单位输出由风险策略定义、具有明确截止时点的交易视图。 */
@Service
public class TransactionWindowService {

    private final CaseRepository caseRepository;

    private final CustomerDataPort customerData;

    private final RiskProperties.Transaction transactionPolicy;

    public TransactionWindowService(CaseRepository caseRepository, CustomerDataPort customerData) {
        this(caseRepository, customerData, new RiskProperties());
    }

    @Autowired
    public TransactionWindowService(CaseRepository caseRepository, CustomerDataPort customerData,
            RiskProperties riskProperties) {
        this.caseRepository = caseRepository;
        this.customerData = customerData;
        this.transactionPolicy = riskProperties.getTransaction();
    }

    @Transactional(readOnly = true)
    public TransactionWindowView windows(Long caseId) {
        CaseEntity caseEntity = caseRepository.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        Instant asOf = customerData.asOfTime();
        LocalDateTime boundary = LocalDateTime.ofInstant(asOf, BusinessZone.ZONE);
        List<TransactionRecord> transactions = customerData.transactionsOf(caseEntity.getCustomerId())
            .stream()
            .filter(transaction -> !transaction.date().isAfter(boundary))
            .toList();
        return new TransactionWindowView(asOf, customerData.sourceSystem(), customerData.sourceVersion(),
                List.of(window(transactions, boundary, transactionPolicy.getShortWindowDays()),
                        window(transactions, boundary, transactionPolicy.getMediumWindowDays()),
                        window(transactions, boundary, transactionPolicy.getLongWindowDays())));
    }

    private TransactionWindowView.Window window(List<TransactionRecord> transactions, LocalDateTime asOf, int days) {
        LocalDateTime start = asOf.minusDays(days);
        List<TransactionRecord> selected = transactions.stream()
            .filter(transaction -> !transaction.date().isBefore(start))
            .toList();
        List<TransactionRecord> crossBorder = selected.stream()
            .filter(transaction -> transaction.country() != null && transaction.country().isCrossBorder())
            .toList();
        long night = selected.stream().filter(transaction -> {
            int hour = transaction.date().getHour();
            return hour >= transactionPolicy.getNightStartHour() || hour < transactionPolicy.getNightEndHour();
        }).count();
        Map<String, List<TransactionRecord>> byCounterparty = selected.stream()
            .collect(Collectors
                .groupingBy(transaction -> transaction.counterparty() == null ? "未知交易对手" : transaction.counterparty()));
        List<TransactionWindowView.CounterpartySummary> top = byCounterparty.entrySet()
            .stream()
            .map(entry -> new TransactionWindowView.CounterpartySummary(entry.getKey(), entry.getValue().size(),
                    amountsByCurrency(entry.getValue())))
            .sorted(Comparator.comparing(TransactionWindowView.CounterpartySummary::transactionCount)
                .reversed()
                .thenComparing(TransactionWindowView.CounterpartySummary::counterparty))
            .limit(transactionPolicy.getTopCounterpartyLimit())
            .toList();
        List<TransactionWindowView.CurrencySummary> currencyBreakdown = selected.stream()
            .collect(Collectors.groupingBy(this::currency))
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> {
                List<TransactionRecord> currencyTransactions = entry.getValue();
                return new TransactionWindowView.CurrencySummary(entry.getKey(), sum(currencyTransactions),
                        sum(currencyTransactions.stream().filter(this::incoming).toList()),
                        sum(currencyTransactions.stream().filter(this::outgoing).toList()),
                        sum(currencyTransactions.stream()
                            .filter(transaction -> transaction.country() != null
                                    && transaction.country().isCrossBorder())
                            .toList()));
            })
            .toList();
        return new TransactionWindowView.Window(days, selected.size(), currencyBreakdown, crossBorder.size(), night,
                top);
    }

    private boolean incoming(TransactionRecord transaction) {
        String direction = transaction.direction();
        return direction != null && (direction.contains("入") || direction.equalsIgnoreCase("IN"));
    }

    private boolean outgoing(TransactionRecord transaction) {
        String direction = transaction.direction();
        return direction != null && (direction.contains("出") || direction.equalsIgnoreCase("OUT"));
    }

    private String currency(TransactionRecord transaction) {
        return transaction.currency() == null || transaction.currency().isBlank() ? "UNKNOWN"
                : transaction.currency().trim().toUpperCase(Locale.ROOT);
    }

    private List<TransactionWindowView.CurrencyAmount> amountsByCurrency(List<TransactionRecord> transactions) {
        return transactions.stream()
            .collect(Collectors.groupingBy(this::currency))
            .entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new TransactionWindowView.CurrencyAmount(entry.getKey(), sum(entry.getValue())))
            .toList();
    }

    private BigDecimal sum(List<TransactionRecord> transactions) {
        return transactions.stream()
            .map(TransactionRecord::amount)
            .filter(Objects::nonNull)
            .map(BigDecimal::abs)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

}
