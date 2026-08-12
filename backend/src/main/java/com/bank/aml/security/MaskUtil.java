package com.bank.aml.security;

/**
 * 敏感信息脱敏工具（姓名 / 证件号 / 账号）。
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    /** 姓名脱敏：张伟 → 张* */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.length() == 1) {
            return "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /** 证件号脱敏：110101198506123456 → 110************6 */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return idCard;
        }
        return idCard.substring(0, 3) + "*".repeat(idCard.length() - 5) + idCard.substring(idCard.length() - 2);
    }

    /** 通用敏感字段脱敏（找不到已知类型则按账户规则） */
    public static String mask(String field, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return switch (field) {
            case "name", "customerName" -> maskName(value);
            case "idCard", "idCardNo" -> maskIdCard(value);
            default -> maskIdCard(value);
        };
    }
}
