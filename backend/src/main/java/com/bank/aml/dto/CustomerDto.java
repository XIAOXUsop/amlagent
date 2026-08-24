package com.bank.aml.dto;

import com.bank.aml.datasource.entity.CustomerEntity;

import java.time.LocalDateTime;

/**
 * 客户/人员管理响应 DTO：证件号仅返回脱敏格式，不暴露明文。
 */
public record CustomerDto(
        Long id,
        String customerNo,
        String name,
        String idCardMasked,
        String type,
        String industry,
        String region,
        String regCapital,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CustomerDto from(CustomerEntity e) {
        return new CustomerDto(e.getId(), e.getCustomerNo(), e.getName(), maskIdCard(e.getIdCard()),
                e.getType(), e.getIndustry(), e.getRegion(), e.getRegCapital(),
                e.getStatus(), e.getCreatedAt(), e.getUpdatedAt());
    }

    /** 证件号脱敏：保留前 6 位与后 4 位，中间以 * 隐藏 */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.isBlank()) {
            return "";
        }
        String v = idCard.trim();
        if (v.length() <= 10) {
            return v.substring(0, 1) + "****";
        }
        return v.substring(0, 6) + "********" + v.substring(v.length() - 4);
    }
}
