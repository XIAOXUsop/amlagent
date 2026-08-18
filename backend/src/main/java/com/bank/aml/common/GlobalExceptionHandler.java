package com.bank.aml.common;

import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.common.exception.RetryableWorkflowException;
import com.bank.aml.common.exception.TooManyRequestsException;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：统一返回 {@link ApiError} 结构，携带 traceId 便于链路追踪。
 * <p>traceId 由 {@link TraceIdFilter} 统一生成并写入请求属性与 MDC，本类只读取不重新生成，
 * 保证响应体 traceId 与日志 MDC、响应头 X-Request-Id 三方一致。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("参数校验失败");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", msg, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "请求体格式错误", req);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex,
                                                       HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权限访问该资源", req);
    }

    @ExceptionHandler(WorkflowStateConflictException.class)
    public ResponseEntity<ApiError> handleStateConflict(WorkflowStateConflictException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "WORKFLOW_STATE_CONFLICT", ex.getMessage(), req);
    }

    /** 登录/鉴权速率限制：429，客户端应停止重试并等待解锁 */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleRateLimited(TooManyRequestsException ex, HttpServletRequest req) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", ex.getMessage(), req);
    }

    /**
     * 不可重试工作流异常：多为客户输入/参数级业务错误（如"客户不存在"）。
     * 映射为 400，并透出可理解的中文 message，避免被当成"服务器内部错误"(500)。
     */
    @ExceptionHandler(NonRetryableWorkflowException.class)
    public ResponseEntity<ApiError> handleNonRetryable(NonRetryableWorkflowException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "BUSINESS_ERROR", ex.getMessage(), req);
    }

    /**
     * 可重试工作流异常属 Worker 内部重试语义；若意外在同步 HTTP 请求中出现，映射为 502
     * （上游依赖不可用），并透出中文原因，而不是笼统的 500。
     */
    @ExceptionHandler(RetryableWorkflowException.class)
    public ResponseEntity<ApiError> handleRetryable(RetryableWorkflowException ex, HttpServletRequest req) {
        log.warn("同步请求透出可重试工作流异常", ex);
        return build(HttpStatus.BAD_GATEWAY, "UPSTREAM_RETRYABLE", ex.getMessage(), req);
    }

    /**
     * {@link IllegalStateException} 表示请求的前置状态不满足（缺环境配置、重复执行等可预期场景）。
     * 映射为 412（前置条件失败）而非 500，让用户能看到具体中文原因而非笼统的"服务器内部错误"。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        return build(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED", ex.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("未处理异常", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误", req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest req) {
        Object attr = req.getAttribute(TraceIdFilter.REQUEST_ATTR_TRACE_ID);
        String traceId = attr instanceof String s && !s.isBlank() ? s : "unknown";
        return ResponseEntity.status(status).body(ApiError.of(code, message, traceId));
    }
}
