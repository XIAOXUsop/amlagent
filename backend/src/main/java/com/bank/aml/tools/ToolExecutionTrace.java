package com.bank.aml.tools;

/**
 * 生产工具调用轨迹：记录工具名、参数是否有效、是否成功、耗时与错误码。
 * <p>不保存工具参数明文（姓名/证件号/原始法规 query），避免敏感字段落库。
 */
public record ToolExecutionTrace(
        String toolName,
        boolean success,
        boolean argumentValid,
        long durationMs,
        String errorCode
) {

    public static ToolExecutionTrace ok(String toolName, long durationMs) {
        return new ToolExecutionTrace(toolName, true, true, durationMs, null);
    }

    public static ToolExecutionTrace invalidArgument(String toolName, long durationMs) {
        return new ToolExecutionTrace(toolName, false, false, durationMs, "ARGUMENT_VALIDATION_FAILED");
    }

    public static ToolExecutionTrace failed(String toolName, long durationMs, String errorCode) {
        return new ToolExecutionTrace(toolName, false, true, durationMs, errorCode);
    }
}
