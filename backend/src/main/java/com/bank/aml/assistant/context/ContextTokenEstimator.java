package com.bank.aml.assistant.context;

/**
 * 上下文 token 估算。
 *
 * <p>抽象成接口是为了让预算层不绑死在某一种分词器上；默认实现在
 * {@link DeterministicTokenEstimator}（零依赖、纯函数）。
 *
 * <p>刻意**不**调用 provider 的 {@code /tokenize} 端点做精确计数：那会让压缩变成
 * 非确定性且依赖网络，直接违反"全离线可测"与"同输入同输出"两条硬约束。
 * 代价是估算偏保守（见默认实现的系数），会略早触发压缩。
 */
public interface ContextTokenEstimator {

    /** 估算器名称，用作指标标签（须全大写，见 MetricsRecorder 的 safeMetricTag 约束） */
    String name();

    int estimateText(String text);

    /** 单条消息的固定开销（角色标记、分隔符等），经验值 */
    int perMessageOverhead();

    default int estimateEntry(ContextEntry entry) {
        return entry.tokenEstimate();
    }
}
