package com.bank.aml.risk;

/**
 * 规则评估上下文：独立的制裁命中与交易聚合数据（不依赖 LLM 判断）。
 */
public record RiskContext(
        /** 命中制裁名单的最高等级（0 = 未命中；1 = 一级制裁；2 = 其他名单） */
        int maxSeverity,
        /** 是否命中任何制裁/可疑名单 */
        boolean sanctionHit,
        /** 跨境交易占比（%） */
        double crossRatio,
        /** 夜间交易占比（%） */
        double nightRatio,
        /** 大额交易笔数（≥100万） */
        long largeCount,
        /** 模型原始评级 */
        String modelRiskLevel,
        /** 模型评级分值（高风险3 / 中风险2 / 低风险1） */
        int modelLevelCode
) {
}
