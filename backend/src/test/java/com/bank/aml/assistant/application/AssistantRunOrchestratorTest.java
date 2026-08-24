package com.bank.aml.assistant.application;

import com.bank.aml.assistant.agent.CustomerAssistantAgentFactory;
import com.bank.aml.assistant.config.AssistantProperties;
import com.bank.aml.assistant.domain.AssistantResultType;
import com.bank.aml.assistant.guard.AssistantInputGuard;
import com.bank.aml.assistant.guard.AssistantOutputGuard;
import com.bank.aml.assistant.guard.SensitiveDataDetector;
import com.bank.aml.assistant.memory.AssistantHistoryLoader;
import com.bank.aml.assistant.memory.ConversationLeaseService;
import com.bank.aml.assistant.snapshot.AssistantSnapshotArchiveService;
import com.bank.aml.assistant.snapshot.CustomerAssistantSnapshotFactory;
import com.bank.aml.security.PromptInjectionGuard;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantRunOrchestratorTest {
    @Test
    void sensitiveInputIsRedactedAndRefusedWithoutSchedulingAgent() {
        AssistantConversationService conversations = mock(AssistantConversationService.class);
        AssistantRunStateService state = mock(AssistantRunStateService.class);
        SensitiveDataDetector sensitive = new SensitiveDataDetector();
        AssistantInputGuard input = new AssistantInputGuard(sensitive, new PromptInjectionGuard());
        AssistantRunExecutor executor = mock(AssistantRunExecutor.class);
        AssistantRunEventPublisher events = mock(AssistantRunEventPublisher.class);
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        when(conversations.acceptMessage(eq("c"), eq("admin"), eq("m-1"), any()))
                .thenReturn(new AssistantConversationService.AcceptedRun("r", "u", "a", false));

        AssistantRunOrchestrator orchestrator = new AssistantRunOrchestrator(
                conversations, state, input, new AssistantOutputGuard(sensitive),
                mock(CustomerAssistantSnapshotFactory.class), mock(AssistantSnapshotArchiveService.class),
                mock(AssistantHistoryLoader.class), mock(CustomerAssistantAgentFactory.class),
                mock(ConversationLeaseService.class), executor, mock(ScheduledExecutorService.class), events, properties);

        orchestrator.submitMessage("c", "admin", "m-1", "身份证110101199001011234");

        verify(conversations).acceptMessage("c", "admin", "m-1", "身份证[敏感信息已遮蔽]");
        verify(state).refuse("r", "请勿在对话中输入或索取完整身份证、账号、银行卡或密钥信息。",
                AssistantResultType.SENSITIVE_DATA_DENIED, "SENSITIVE_DATA_REQUEST");
        verify(executor, never()).submit(any());
        verify(events).refused(eq("r"), eq(AssistantResultType.SENSITIVE_DATA_DENIED), any());
    }
}
