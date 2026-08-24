package com.bank.aml.assistant.agent;

import java.util.List;

public record AssistantToolTrace(long sequenceNo, String toolName, String status, long durationMs,
                                 String resultDigest, List<String> evidenceIds, String errorCode) {
    public AssistantToolTrace {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
