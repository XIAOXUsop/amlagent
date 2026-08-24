package com.bank.aml.assistant.api;

import com.bank.aml.assistant.application.AssistantConversationService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/customers/{customerId}/assistant/conversations")
@PreAuthorize("hasRole('ADMIN')")
public class CustomerAssistantController {
    private final AssistantConversationService conversations;

    public CustomerAssistantController(AssistantConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AssistantDtos.ConversationResponse create(@PathVariable Long customerId) {
        return AssistantDtos.ConversationResponse.from(conversations.create(customerId, operator()));
    }

    @GetMapping
    public Page<AssistantDtos.ConversationResponse> list(@PathVariable Long customerId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return conversations.list(customerId, operator(), page, size).map(AssistantDtos.ConversationResponse::from);
    }

    private String operator() { return SecurityContextHolder.getContext().getAuthentication().getName(); }
}
