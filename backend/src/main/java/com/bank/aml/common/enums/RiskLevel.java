package com.bank.aml.common.enums;

import java.util.Optional;

/**
 * 客户风险等级统一枚举：作为 Guardrails / 规则引擎 / 评测三方共享的单一事实来源，
 * 消除散落各处的"低风险/中风险/高风险"魔法字符串与等级分值映射。
 * <p>存储与 API 契约仍使用中文文案（低风险/中风险/高风险），本枚举只负责文案↔分值↔序数换算。
 */
public enum RiskLevel {

    LOW("低风险", 1),
    MEDIUM("中风险", 2),
    HIGH("高风险", 3);

    private final String label;
    private final int code;

    RiskLevel(String label, int code) {
        this.label = label;
        this.code = code;
    }

    public String label() {
        return label;
    }

    /** 等级分值：低风险 1 / 中风险 2 / 高风险 3，用于"只升不降"比较 */
    public int code() {
        return code;
    }

    /** 未识别文案（null/空白/未知）按低风险处理，避免 NPE 与异常破坏规则评估 */
    public static RiskLevel fromLabel(String label) {
        return Optional.ofNullable(label)
                .map(String::trim)
                .flatMap(l -> java.util.Arrays.stream(values())
                        .filter(v -> v.label.equals(l))
                        .findFirst())
                .orElse(LOW);
    }

    /** 从分值还原等级；未知分值兜底低风险 */
    public static RiskLevel fromCode(int code) {
        for (RiskLevel level : values()) {
            if (level.code == code) {
                return level;
            }
        }
        return LOW;
    }
}
