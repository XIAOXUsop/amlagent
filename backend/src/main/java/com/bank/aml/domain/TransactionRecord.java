package com.bank.aml.domain;

import com.bank.aml.common.enums.CountryRegion;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 单笔交易记录（金融安全类型：金额 {@link BigDecimal}、时间 {@link LocalDateTime}）。
 *
 * @param sourceRecordId 源系统交易身份（v3 计划 §9.1；数据库交易为权威主键，冻结后用于范围对账与去重）。 字段不可取得时为
 * null（显式未知，不得编造）。
 */
public record TransactionRecord(LocalDateTime date, BigDecimal amount, String direction, String counterparty,
        CountryRegion country, String channel, String scene, String currency, String sourceRecordId) {
    /** 兼容既有构造（无源身份）；身份不可用时 sourceRecordId=null。 */
    public TransactionRecord(LocalDateTime date, BigDecimal amount, String direction, String counterparty,
            CountryRegion country, String channel, String scene, String currency) {
        this(date, amount, direction, counterparty, country, channel, scene, currency, null);
    }
}
