package com.bank.aml.assistant.api;

import com.bank.aml.assistant.application.AssistantConversationService;
import com.bank.aml.assistant.application.AssistantRateLimiter;
import com.bank.aml.assistant.application.AssistantRunOrchestrator;
import com.bank.aml.assistant.config.AssistantProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/assistant")
@PreAuthorize("hasRole('ADMIN')")
public class AssistantConversationController {
    private final AssistantConversationService conversations;
    private final AssistantRunOrchestrator orchestrator;
    private final AssistantRateLimiter rateLimiter;
    private final AssistantProperties properties;

    public AssistantConversationController(AssistantConversationService conversations,
                                           AssistantRunOrchestrator orchestrator,
                                           AssistantRateLimiter rateLimiter,
                                           AssistantProperties properties) {
        this.conversations = conversations;
        this.orchestrator = orchestrator;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** 功能开关状态必须在关闭时也可读取，供前端隐藏入口。 */
    @GetMapping("/status")
    public AssistantDtos.StatusResponse status() {
        return new AssistantDtos.StatusResponse(properties.isEnabled(), properties.getMaxMessageChars());
    }

    @GetMapping("/conversations/{conversationId}")
    public AssistantDtos.ConversationResponse conversation(@PathVariable String conversationId) {
        return AssistantDtos.ConversationResponse.from(conversations.get(conversationId, operator()));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<AssistantDtos.MessageResponse> messages(@PathVariable String conversationId) {
        return conversations.messages(conversationId, operator()).stream().map(AssistantDtos.MessageResponse::from).toList();
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<AssistantDtos.AcceptedRunResponse> submit(@PathVariable String conversationId,
                                                                   @Valid @RequestBody SubmitMessageRequest request) {
        String operator = operator();
        rateLimiter.check(operator);
        var accepted = orchestrator.submitMessage(conversationId, operator,
                request.clientMessageId(), request.content());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AssistantDtos.AcceptedRunResponse(
                accepted.runId(), accepted.userMessageId(), accepted.assistantMessageId(),
                "ACCEPTED", accepted.idempotentReplay()));
    }

    @DeleteMapping("/conversations/{conversationId}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable String conversationId) {
        conversations.archive(conversationId, operator());
    }

    private String operator() { return SecurityContextHolder.getContext().getAuthentication().getName(); }

    public record SubmitMessageRequest(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9_-]+", message = "clientMessageId 格式无效") String clientMessageId,
            @NotBlank @Size(max = 10_000) String content) {}
}
