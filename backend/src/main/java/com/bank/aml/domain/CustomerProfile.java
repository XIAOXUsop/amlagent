package com.bank.aml.domain;

/**
 * 客户画像领域模型（与具体数据源解耦）。
 */
public record CustomerProfile(
        String id,
        String name,
        String idCard,
        String type,
        String industry,
        String region,
        String regCapital
) {
}
