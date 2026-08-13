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
        /** 交易数据是否完整；缺失时不得把“未发现异常”当成低风险证据。 */
        boolean transactionDataComplete,
        /** 跨境、夜间等交易特征是否已有可信业务材料解释。 */
        boolean transactionRiskExplained,
        /** 可疑交易模式严重度：0=无，1=需加强监测，2=高风险模式。 */
        int transactionPatternSeverity,
        /** 受益所有人风险严重度：0=已核实，1=材料待更新，2=无法可靠核实。 */
        int uboRiskSeverity,
        /** 模型原始评级 */
        String modelRiskLevel,
        /** 模型评级分值（高风险3 / 中风险2 / 低风险1） */
        int modelLevelCode
) {
    /**
     * 兼容仅提供聚合交易指标的生产调用方。默认认为数据完整、风险尚未被业务材料解释，
     * 且没有额外的交易模式或 UBO 结构化信号。
     */
    public RiskContext(int maxSeverity, boolean sanctionHit, double crossRatio, double nightRatio,
                       long largeCount, String modelRiskLevel, int modelLevelCode) {
        this(maxSeverity, sanctionHit, crossRatio, nightRatio, largeCount,
                true, false, 0, 0, modelRiskLevel, modelLevelCode);
    }
}
