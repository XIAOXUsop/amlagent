package com.bank.aml.assistant.application;

import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import com.bank.aml.assistant.persistence.repository.AssistantRunRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantRunRecoveryServiceTest {
    @Test
    void failsAbandonedRunsWithoutReplayingModel() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        AssistantRunRepository runs = mock(AssistantRunRepository.class);
        AssistantRunStateService state = mock(AssistantRunStateService.class);
        AssistantRunEntity run = AssistantRunEntity.accepted("c", "u", "a");
        when(runs.findTop100ByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(run));

        new AssistantRunRecoveryService(properties, runs, state).recoverTimedOutRuns();

        verify(state).fail(eq(run.getId()), anyString(), eq("APPLICATION_RESTARTED"), eq(0L));
        verifyNoMoreInteractions(state);
    }

    @Test
    void doesNothingWhenFeatureIsDisabled() {
        AssistantProperties properties = new AssistantProperties();
        AssistantRunRepository runs = mock(AssistantRunRepository.class);
        new AssistantRunRecoveryService(properties, runs, mock(AssistantRunStateService.class)).recoverTimedOutRuns();
        verifyNoInteractions(runs);
    }
}
