package com.bank.aml.assistant.application;

import com.bank.aml.assistant.agent.AssistantToolTrace;
import com.bank.aml.assistant.persistence.entity.AssistantMessageEntity;
import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import com.bank.aml.assistant.persistence.repository.AssistantConversationRepository;
import com.bank.aml.assistant.persistence.repository.AssistantMessageRepository;
import com.bank.aml.assistant.persistence.repository.AssistantRunRepository;
import com.bank.aml.assistant.persistence.repository.AssistantToolTraceRepository;
import com.bank.aml.config.LlmProperties;
import com.bank.aml.observability.MetricsRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantRunStateServiceTest {

    @Test
    void failedRunPersistsCompletedAndInvalidToolTraces() {
        AssistantRunRepository runs = mock(AssistantRunRepository.class);
        AssistantMessageRepository messages = mock(AssistantMessageRepository.class);
        AssistantToolTraceRepository traces = mock(AssistantToolTraceRepository.class);
        AssistantRunEntity run = mock(AssistantRunEntity.class);
        AssistantMessageEntity answer = mock(AssistantMessageEntity.class);
        when(runs.findById("run-1")).thenReturn(Optional.of(run));
        when(run.getAssistantMessageId()).thenReturn("answer-1");
        when(run.getIntent()).thenReturn("CUSTOMER_ANALYSIS");
        when(messages.findById("answer-1")).thenReturn(Optional.of(answer));
        AssistantRunStateService state = new AssistantRunStateService(runs,
                mock(AssistantConversationRepository.class), messages, traces, new ObjectMapper(),
                mock(LlmProperties.class), mock(MetricsRecorder.class));

        state.fail("run-1", "safe", "MODEL_ERROR", 123,
                List.of(new AssistantToolTrace(1, "getCurrentEvidence", "INVALID_ARGUMENT",
                        2, null, List.of(), "INVALID_ARGUMENT")));

        verify(answer).fail("safe", com.bank.aml.assistant.domain.AssistantResultType.MODEL_UNAVAILABLE);
        verify(traces).save(argThat(trace -> trace.getSequenceNo() == 1
                && "getCurrentEvidence".equals(trace.getToolName())
                && "INVALID_ARGUMENT".equals(trace.getStatus())
                && "INVALID_ARGUMENT".equals(trace.getErrorCode())));
    }
}
