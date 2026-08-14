package com.bank.aml.tools;

import java.util.List;

/**
 * 生产工具调用轨迹：记录工具名、参数是否有效、是否成功、耗时、错误码、结果摘要哈希与返回 evidenceId。
 * <p>不保存工具参数明文（姓名/证件号/原始法规 query）与完整结果，避免敏感字段落库。
 */
public record ToolExecutionTrace(
        String toolName,
        boolean success,
        boolean argumentValid,
        long durationMs,
        String errorCode,
        String resultDigest,
        List<String> evidenceIds
) {

    public static ToolExecutionTrace ok(String toolName, long durationMs) {
        return new ToolExecutionTrace(toolName, true, true, durationMs, null, null, List.of());
    }

    public static ToolExecutionTrace ok(String toolName, long durationMs, String resultDigest, List<String> evidenceIds) {
        return new ToolExecutionTrace(toolName, true, true, durationMs, null, resultDigest, evidenceIds);
    }

    public static ToolExecutionTrace invalidArgument(String toolName, long durationMs) {
        return new ToolExecutionTrace(toolName, false, false, durationMs, "ARGUMENT_VALIDATION_FAILED", null, List.of());
    }

    public static ToolExecutionTrace failed(String toolName, long durationMs, String errorCode) {
        return new ToolExecutionTrace(toolName, false, true, durationMs, errorCode, null, List.of());
    }
}
