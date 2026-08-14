package com.bank.aml.domain;

/**
 * 制裁名单条目。
 */
public record SanctionRecord(
        String name,
        String idCard,
        String listType,
        String detail,
        int severity
) {
}
