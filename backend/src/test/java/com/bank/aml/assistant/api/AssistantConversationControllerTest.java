package com.bank.aml.assistant.api;

import com.bank.aml.assistant.application.AssistantConversationService;
import com.bank.aml.assistant.application.AssistantRateLimiter;
import com.bank.aml.assistant.application.AssistantRunOrchestrator;
import com.bank.aml.assistant.config.AssistantProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantConversationControllerTest {
    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void submitUsesAuthenticatedOperatorAndReturnsAcceptedRun() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("admin", null, java.util.List.of()));
        AssistantConversationService conversations = mock(AssistantConversationService.class);
        AssistantRunOrchestrator orchestrator = mock(AssistantRunOrchestrator.class);
        AssistantRateLimiter limiter = mock(AssistantRateLimiter.class);
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(true);
        when(orchestrator.submitMessage("c-1", "admin", "client-1", "分析当前客户交易"))
                .thenReturn(new AssistantConversationService.AcceptedRun("r-1", "u-1", "a-1", false));
        var controller = new AssistantConversationController(conversations, orchestrator, limiter, properties);

        var response = controller.submit("c-1",
                new AssistantConversationController.SubmitMessageRequest("client-1", "分析当前客户交易"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().runId()).isEqualTo("r-1");
        verify(limiter).check("admin");
        verify(orchestrator).submitMessage("c-1", "admin", "client-1", "分析当前客户交易");
    }

    @Test
    void statusRemainsReadableWhenFeatureIsDisabled() {
        AssistantProperties properties = new AssistantProperties();
        var controller = new AssistantConversationController(mock(AssistantConversationService.class),
                mock(AssistantRunOrchestrator.class), mock(AssistantRateLimiter.class), properties);
        assertThat(controller.status().enabled()).isFalse();
    }
}
