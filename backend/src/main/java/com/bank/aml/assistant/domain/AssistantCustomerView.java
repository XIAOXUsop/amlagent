package com.bank.aml.assistant.domain;

/** 提供给模型的最小客户画像；刻意不包含姓名、证件号、账号和数据库主键。 */
public record AssistantCustomerView(
        String reference,
        String customerType,
        String industry,
        String region,
        String registeredCapital,
        String status
) {}
