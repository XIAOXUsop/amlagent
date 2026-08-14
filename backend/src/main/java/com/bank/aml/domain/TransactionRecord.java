package com.bank.aml.domain;

import com.bank.aml.common.enums.CountryRegion;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单笔交易记录（金融安全类型：金额 {@link BigDecimal}、时间 {@link LocalDateTime}）。
 */
public record TransactionRecord(
        LocalDateTime date,
        BigDecimal amount,
        String direction,
        String counterparty,
        CountryRegion country,
        String channel,
        String scene,
        String currency
) {
}
