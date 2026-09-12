package com.bank.aml.sanction;

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

/** 制裁筛查领域异常的 HTTP 映射。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SanctionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SanctionExceptionHandler.class);

    private final Clock clock;

    public SanctionExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(SanctionReviewConflictException.class)
    public ResponseEntity<ApiError> conflict(SanctionReviewConflictException exception, HttpServletRequest request) {
        Object attribute = request.getAttribute(TraceIdFilter.REQUEST_ATTR_TRACE_ID);
        String traceId = attribute instanceof String value && !value.isBlank() ? value : "unknown";
        log.warn("制裁候选复核版本冲突，traceId={} type={}", traceId, exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("SANCTION_REVIEW_CONFLICT", "制裁候选已被更新，请刷新后重新复核", traceId, clock.instant()));
    }

}
