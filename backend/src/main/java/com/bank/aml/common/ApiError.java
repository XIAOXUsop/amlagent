package com.bank.aml.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 统一错误响应体。
 *
 * @param conflict 版本冲突等场景的有界补充信息；无冲突时为 null 且不出现在 JSON 中
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        /** 业务错误码，如 CASE_ALREADY_RUNNING */
        String code,
        String message,
        String traceId,
        LocalDateTime timestamp,
        Conflict conflict
) {
    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, traceId, LocalDateTime.now(), null);
    }

    public static ApiError of(String code, String message, String traceId, Conflict conflict) {
        return new ApiError(code, message, traceId, LocalDateTime.now(), conflict);
    }

    /** 有界的冲突对象信息（调查版本冲突协议），供客户端刷新到正确版本。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Conflict(
            /** 冲突对象类型：HYPOTHESIS（判断依据假设改判）或 COVERAGE（覆盖被他人确认） */
            String type,
            /** 冲突对象标识：假设 ID 或预警 ID */
            Long id,
            /** 服务端当前版本，客户端刷新后应以此为准 */
            Integer currentVersion
    ) {
    }
}
