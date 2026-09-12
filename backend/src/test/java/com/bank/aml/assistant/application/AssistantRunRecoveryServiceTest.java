package com.bank.aml.assistant.application;

import com.bank.aml.TestClocks;
import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.persistence.entity.AssistantRunEntity;
import com.bank.aml.assistant.persistence.repository.AssistantRunRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AssistantRunRecoveryServiceTest {

    @Test
    void failsAbandonedRunsWithoutReplayingModel() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        AssistantRunRepository runs = mock(AssistantRunRepository.class);
        AssistantRunStateService state = mock(AssistantRunStateService.class);
        AssistantRunEntity run = AssistantRunEntity.accepted("c", "u", "a");
        when(runs.findTop100ByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(run));

        new AssistantRunRecoveryService(properties, runs, state, TestClocks.FIXED).recoverTimedOutRuns();

        verify(state).fail(eq(run.getId()), anyString(), eq("APPLICATION_RESTARTED"), eq(0L));
        verifyNoMoreInteractions(state);
    }

    @Test
    void doesNothingWhenFeatureIsDisabled() {
        AssistantProperties properties = new AssistantProperties();
        AssistantRunRepository runs = mock(AssistantRunRepository.class);
        new AssistantRunRecoveryService(properties, runs, mock(AssistantRunStateService.class), TestClocks.FIXED)
            .recoverTimedOutRuns();
        verifyNoInteractions(runs);
    }

}
