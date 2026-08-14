package com.bank.aml.domain;

import java.math.BigDecimal;

/**
 * 股权关系记录。
 */
public record ShareholdingRecord(
        String holder,
        String holderType,
        BigDecimal ratio,
        String level
) {
}
