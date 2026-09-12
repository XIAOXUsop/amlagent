package com.bank.aml.common;

import com.bank.aml.common.exception.CustomerNotFoundException;
import com.bank.aml.common.exception.InvestigationRevisionConflictException;
import com.bank.aml.common.exception.NonRetryableWorkflowException;
import com.bank.aml.common.exception.RetryableWorkflowException;
import com.bank.aml.common.exception.TooManyRequestsException;
import com.bank.aml.common.exception.WorkflowStateConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：统一返回 {@link ApiError} 结构，携带 traceId 便于链路追踪。
 * <p>
 * traceId 由 {@link TraceIdFilter} 统一生成并写入请求属性与 MDC，本类只读取不重新生成， 保证响应体 traceId 与日志 MDC、响应头
 * X-Request-Id 三方一致。
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("请求参数或业务输入不合法，traceId={} type={}", traceId(req), ex.getClass().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求参数不合法", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult()
            .getFieldErrors()
            .forEach(error -> fieldErrors.putIfAbsent(error.getField(),
                    error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError.of("VALIDATION_ERROR", "参数校验失败", traceId(req), clock.instant(), fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "请求体格式错误", req);
    }

    @ExceptionHandler({ MethodArgumentTypeMismatchException.class, ServletRequestBindingException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class })
    public ResponseEntity<ApiError> handleRequestContractViolation(Exception ex, HttpServletRequest req) {
        log.warn("请求参数协议校验失败，traceId={} type={}", traceId(req), ex.getClass().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败", req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权限访问该资源", req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailure(AuthenticationException ex, HttpServletRequest req) {
        log.warn("认证失败，traceId={} type={}", traceId(req), ex.getClass().getSimpleName());
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误", req);
    }

    @ExceptionHandler(WorkflowStateConflictException.class)
    public ResponseEntity<ApiError> handleStateConflict(WorkflowStateConflictException ex, HttpServletRequest req) {
        log.warn("工作流状态冲突，traceId={} type={}", traceId(req), ex.getClass().getSimpleName());
        return build(HttpStatus.CONFLICT, "WORKFLOW_STATE_CONFLICT", "工单状态已发生变化，请刷新后重试", req);
    }

    /**
     * 调查版本冲突：覆盖或其引用的假设版本已变化。409 + 稳定错误码， 并附上有界冲突对象信息（类型/标识/当前版本），客户端刷新后由用户明确重新确认。
     */
    @ExceptionHandler(InvestigationRevisionConflictException.class)
    public ResponseEntity<ApiError> handleInvestigationRevisionConflict(InvestigationRevisionConflictException ex,
            HttpServletRequest req) {
        ApiError.Conflict conflict = ex.getConflictType() == null ? null
                : new ApiError.Conflict(ex.getConflictType(), ex.getConflictId(), ex.getCurrentVersion());
        log.warn("调查版本冲突，traceId={} conflictType={} conflictId={} currentVersion={}", traceId(req),
                ex.getConflictType(), ex.getConflictId(), ex.getCurrentVersion());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("INVESTIGATION_REVISION_CONFLICT", "调查事实已发生变化，请刷新后重新确认", traceId(req), clock.instant(),
                    conflict));
    }

    /** 登录/鉴权速率限制：429，客户端应停止重试并等待解锁 */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleRateLimited(TooManyRequestsException ex, HttpServletRequest req) {
        log.warn("请求触发速率限制，traceId={} type={}", traceId(req), ex.getClass().getSimpleName());
        return build(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后再试", req);
    }

    /**
     * 不可重试工作流异常：多为客户输入或参数级业务错误。映射为 400，返回稳定的安全文案， 原始异常消息既不进入响应，也不写入日志。
     */
    @ExceptionHandler(NonRetryableWorkflowException.class)
    public ResponseEntity<ApiError> handleNonRetryable(NonRetryableWorkflowException ex, HttpServletRequest req) {
        log.warn("业务请求无法处理，traceId={} type={}", traceId(req), ex.getClass().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, "BUSINESS_ERROR", "业务请求无法处理，请检查输入后重试", req);
    }

    /**
     * 可重试工作流异常属 Worker 内部重试语义；若意外在同步 HTTP 请求中出现，映射为 502 （上游依赖不可用），仅返回稳定错误码和安全文案。
     */
    @ExceptionHandler(RetryableWorkflowException.class)
    public ResponseEntity<ApiError> handleRetryable(RetryableWorkflowException ex, HttpServletRequest req) {
        log.warn("同步请求出现可重试工作流异常，traceId={} type={}", traceId(req), ex.getClass().getSimpleName());
        return build(HttpStatus.BAD_GATEWAY, "UPSTREAM_RETRYABLE", "上游服务暂时不可用，请稍后重试", req);
    }

    /**
     * 未分类的 {@link IllegalStateException} 可能包含数据库、文件系统或第三方组件细节，必须按内部错误处理。
     * 可预期的业务状态冲突应使用带稳定错误码的领域异常，不得借此处理器向客户端透传原始消息。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest req) {
        log.error("未分类的内部状态异常，traceId={}", traceId(req), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("未处理异常", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务器内部错误", req);
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<ApiError> handleCustomerNotFound(CustomerNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "客户不存在", req);
    }

    private String traceId(HttpServletRequest req) {
        Object attr = req.getAttribute(TraceIdFilter.REQUEST_ATTR_TRACE_ID);
        return attr instanceof String s && !s.isBlank() ? s : "unknown";
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest req) {
        return ResponseEntity.status(status).body(ApiError.of(code, message, traceId(req), clock.instant()));
    }

}
