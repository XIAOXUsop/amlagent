package com.bank.aml.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** 在 Spring MVC 之外的安全过滤器边界写出统一错误响应。 */
@Component
public class ApiErrorResponseWriter {

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public ApiErrorResponseWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(code, message, traceId(request), clock.instant()));
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.REQUEST_ATTR_TRACE_ID);
        return value instanceof String traceId && !traceId.isBlank() ? traceId : "unknown";
    }

}
