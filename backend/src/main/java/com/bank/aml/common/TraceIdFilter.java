package com.bank.aml.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个 HTTP 请求生成 traceId 并写入 MDC，贯穿整条请求线程的日志（含异步回调由 MDC 继承）。
 * <p>请求结束前清理，避免 MDC 泄漏到下一条请求。配合日志格式中的 %X{traceId}，
 * 可在日志中按一次请求聚合关联排查。
 * <p>同一 traceId 会写入 {@link HttpServletRequest} 属性与响应头 {@code X-Request-Id}，
 * 使 {@code GlobalExceptionHandler} 返回的 traceId 与日志 MDC、下游透传三方保持一致，
 * 便于端到端关联。客户端透传的 X-Request-Id 需通过字符/长度白名单校验，防止日志注入。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Request-Id";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String REQUEST_ATTR_TRACE_ID = TraceIdFilter.class.getName() + ".traceId";

    private static final Pattern ALLOWED_TRACE_ID = Pattern.compile("^[A-Za-z0-9._\\-]{1,64}$");
    private static final int MAX_LEN = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(TRACE_HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        request.setAttribute(REQUEST_ATTR_TRACE_ID, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        MDC.put(MDC_TRACE_ID, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /** 校验透传 traceId：仅接受安全字符且长度受限，非法/超长一律忽略（重新生成），防止日志注入。 */
    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_LEN && ALLOWED_TRACE_ID.matcher(trimmed).matches() ? trimmed : null;
    }
}
