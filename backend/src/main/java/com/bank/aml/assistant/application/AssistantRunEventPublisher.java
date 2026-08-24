package com.bank.aml.assistant.application;

import com.bank.aml.assistant.domain.AssistantResultType;

public interface AssistantRunEventPublisher {
    void started(String runId, String assistantMessageId);
    void delta(String runId, String text);
    void completed(String runId, AssistantResultType resultType);
    void refused(String runId, AssistantResultType resultType, String message);
    void failed(String runId, String errorCode);
}
