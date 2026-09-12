package com.bank.aml.assistant.api;

import com.bank.aml.assistant.application.AssistantDisabledException;
import com.bank.aml.assistant.application.AssistantRateLimitException;
import com.bank.aml.assistant.application.ConversationBusyException;
import com.bank.aml.assistant.application.ConversationNotFoundException;
import com.bank.aml.assistant.application.ConversationStateException;
import com.bank.aml.common.ApiError;
import com.bank.aml.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将助手领域异常转换为统一 HTTP 错误契约，避免 common 反向依赖业务模块。 */
@RestControllerAdvice(basePackageClasses = AssistantConversationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AssistantExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AssistantExceptionHandler.class);

    private final Clock clock;

    public AssistantExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(AssistantDisabledException.class)
    public ResponseEntity<ApiError> disabled(AssistantDisabledException exception, HttpServletRequest request) {
        log.warn("AI 小助未启用，traceId={} type={}", traceId(request), exception.getClass().getSimpleName());
        return build(HttpStatus.PRECONDITION_FAILED, "ASSISTANT_DISABLED", "AI 小助当前未启用", request);
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ConversationNotFoundException exception, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "会话不存在", request);
    }

    @ExceptionHandler(ConversationBusyException.class)
    public ResponseEntity<ApiError> busy(ConversationBusyException exception, HttpServletRequest request) {
        log.warn("AI 小助会话忙，traceId={} type={}", traceId(request), exception.getClass().getSimpleName());
        return build(HttpStatus.CONFLICT, "CONVERSATION_BUSY", "当前会话已有问题正在分析", request);
    }

    @ExceptionHandler(ConversationStateException.class)
    public ResponseEntity<ApiError> state(ConversationStateException exception, HttpServletRequest request) {
        log.warn("AI 小助会话状态冲突，traceId={} type={}", traceId(request), exception.getClass().getSimpleName());
        String code = switch (exception.code()) {
            case "CONVERSATION_ARCHIVED" -> "CONVERSATION_ARCHIVED";
            case "CONVERSATION_EXPIRED" -> "CONVERSATION_EXPIRED";
            default -> "CONVERSATION_STATE_CONFLICT";
        };
        String message = switch (code) {
            case "CONVERSATION_ARCHIVED" -> "会话已归档";
            case "CONVERSATION_EXPIRED" -> "会话已过期";
            default -> "会话状态已变化，请刷新后重试";
        };
        return build(HttpStatus.CONFLICT, code, message, request);
    }

    @ExceptionHandler(AssistantRateLimitException.class)
    public ResponseEntity<ApiError> rateLimited(AssistantRateLimitException exception, HttpServletRequest request) {
        log.warn("AI 小助请求触发速率限制，traceId={} type={}", traceId(request), exception.getClass().getSimpleName());
        return build(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_RATE_LIMITED", "AI 小助请求过于频繁，请稍后再试", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.of(code, message, traceId(request), clock.instant()));
    }

    private String traceId(HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.REQUEST_ATTR_TRACE_ID);
        return attribute instanceof String value && !value.isBlank() ? value : "unknown";
    }

}
