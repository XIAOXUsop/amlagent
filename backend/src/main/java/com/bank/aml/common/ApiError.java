package com.bank.aml.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * 统一错误响应体。
 *
 * @param fieldErrors 字段校验错误；无字段错误时为 null 且不出现在 JSON 中
 * @param conflict 版本冲突等场景的有界补充信息；无冲突时为 null 且不出现在 JSON 中
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(/** 业务错误码，如 CASE_ALREADY_RUNNING */
String code, String message, String traceId, Instant timestamp, Map<String, String> fieldErrors, Conflict conflict) {

    public ApiError {
        fieldErrors = fieldErrors == null || fieldErrors.isEmpty() ? null : Map.copyOf(fieldErrors);
    }

    public static ApiError of(String code, String message, String traceId, Instant timestamp) {
        return new ApiError(code, message, traceId, timestamp, null, null);
    }

    public static ApiError of(String code, String message, String traceId, Instant timestamp,
            Map<String, String> fieldErrors) {
        return new ApiError(code, message, traceId, timestamp, fieldErrors, null);
    }

    public static ApiError of(String code, String message, String traceId, Instant timestamp, Conflict conflict) {
        return new ApiError(code, message, traceId, timestamp, null, conflict);
    }

    /** 有界的冲突对象信息（调查版本冲突协议），供客户端刷新到正确版本。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Conflict(
            /** 冲突对象类型：HYPOTHESIS（判断依据假设改判）或 COVERAGE（覆盖被他人确认） */
            String type,
            /** 冲突对象标识：假设 ID 或预警 ID */
            Long id,
            /** 服务端当前版本，客户端刷新后应以此为准 */
            Integer currentVersion) {
    }
}
