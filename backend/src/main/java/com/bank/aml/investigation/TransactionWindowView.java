package com.bank.aml.investigation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TransactionWindowView(Instant asOfTime, String sourceSystem, String sourceVersion, List<Window> windows) {
    public record Window(int days, long transactionCount, List<CurrencySummary> currencyBreakdown,
            long crossBorderCount, long nightCount, List<CounterpartySummary> topCounterparties) {
    }

    public record CurrencySummary(String currency, BigDecimal totalAmount, BigDecimal incomingAmount,
            BigDecimal outgoingAmount, BigDecimal crossBorderAmount) {
    }

    public record CurrencyAmount(String currency, BigDecimal amount) {
    }

    public record CounterpartySummary(String counterparty, long transactionCount, List<CurrencyAmount> amounts) {
    }
}
