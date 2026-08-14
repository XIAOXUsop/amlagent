package com.bank.aml.common;

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

import java.util.UUID;

/**
 * 全局异常处理：统一返回 {@link ApiError} 结构，携带 traceId 便于链路追踪。
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("未处理异常 traceId={}", req.getAttribute("traceId"), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误", req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest req) {
        String traceId = UUID.randomUUID().toString();
        req.setAttribute("traceId", traceId);
        return ResponseEntity.status(status).body(ApiError.of(code, message, traceId));
    }
}
