package com.bank.aml.common;

import java.time.LocalDateTime;

/**
 * 统一错误响应体。
 */
public record ApiError(
        /** 业务错误码，如 CASE_ALREADY_RUNNING */
        String code,
        String message,
        String traceId,
        LocalDateTime timestamp
) {
    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, traceId, LocalDateTime.now());
    }
}
