package com.bank.aml.assistant.domain;

import java.math.BigDecimal;
import java.util.List;

public record TransactionRiskView(
        int transactionCount,
        BigDecimal totalAmount,
        BigDecimal averageAmount,
        double nightRatio,
        double crossBorderRatio,
        long largeTransactionCount,
        int patternSeverity,
        List<String> currencies,
        List<String> regions,
        boolean dataComplete
) {
    public TransactionRiskView {
        currencies = currencies == null ? List.of() : List.copyOf(currencies);
        regions = regions == null ? List.of() : List.copyOf(regions);
    }
}
