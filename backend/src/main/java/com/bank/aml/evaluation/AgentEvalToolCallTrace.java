package com.bank.aml.evaluation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One immutable tool invocation captured during an Agent evaluation case.
 *
 * <p>The result itself is intentionally not retained: a digest is enough to prove which fixture
 * response was returned without duplicating potentially sensitive fixture data in reports.</p>
 */
public record AgentEvalToolCallTrace(
        String toolName,
        Map<String, String> arguments,
        boolean success,
        boolean argumentValid,
        long durationMs,
        String resultDigest,
        String error
) {

    public AgentEvalToolCallTrace {
        Map<String, String> sanitized = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((name, value) -> sanitized.put(name, sanitize(name, value)));
        }
        arguments = Collections.unmodifiableMap(sanitized);
    }

    private static String sanitize(String name, String value) {
        if (value == null) {
            return null;
        }
        String normalized = name == null ? "" : name.toLowerCase();
        if (normalized.contains("identity") || normalized.contains("idcard")
                || normalized.contains("name") || normalized.contains("customer")) {
            return "[REDACTED]";
        }
        if (normalized.contains("query")) {
            return "[QUERY_REDACTED]";
        }
        return value;
    }
}
