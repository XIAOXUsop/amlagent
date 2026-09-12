package com.bank.aml.assistant.context;

/**
 * 上下文预算。
 *
 * <p>
 * 总预算 = provider 上下文窗口 − 输出预留 − 工具往返预留 − system prompt − 本轮用户输入，
 * 再乘一个保守系数（估算误差的缓冲）。剩余部分才是"可自由裁量"的历史预算。
 *
 * @param providerContextWindow 模型上下文窗口
 * @param outputReserve 输出预留（不参与治理，永远留给模型回答）
 * @param toolRoundReserve 工具往返预留（含历史取回工具的调用）
 * @param systemPromptTokens system prompt 实际占用
 * @param currentTurnTokens 本轮用户输入占用（pinned，不可动）
 * @param safetyMarginPercent 保守系数百分比（0..50）
 */
public record ContextBudget(int providerContextWindow, int outputReserve, int toolRoundReserve, int systemPromptTokens,
        int currentTurnTokens, int safetyMarginPercent) {

    public ContextBudget {
        if (outputReserve + toolRoundReserve >= providerContextWindow) {
            throw new IllegalArgumentException(
                    "输出预留与工具预留之和必须小于上下文窗口：" + (outputReserve + toolRoundReserve) + " >= " + providerContextWindow);
        }
        if (safetyMarginPercent < 0 || safetyMarginPercent > 50) {
            throw new IllegalArgumentException("保守系数必须在 0..50：" + safetyMarginPercent);
        }
    }

    /** 固定成本：这些 token 与历史无关，治理器动不了 */
    public int fixedCost() {
        return systemPromptTokens + currentTurnTokens + outputReserve + toolRoundReserve;
    }

    /** 可自由裁量的历史预算（已扣除保守系数） */
    public int discretionaryTokens() {
        int usable = providerContextWindow - fixedCost();
        int afterMargin = (int) Math.floor(usable * (100 - safetyMarginPercent) / 100.0);
        return Math.max(0, afterMargin);
    }
}
