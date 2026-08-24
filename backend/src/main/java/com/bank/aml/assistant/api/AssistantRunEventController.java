package com.bank.aml.assistant.api;

import com.bank.aml.assistant.application.AssistantRunQueryService;
import com.bank.aml.assistant.streaming.RedisAssistantEventService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/assistant/runs")
@PreAuthorize("hasRole('ADMIN')")
public class AssistantRunEventController {
    private final AssistantRunQueryService runs;
    private final RedisAssistantEventService events;

    public AssistantRunEventController(AssistantRunQueryService runs, RedisAssistantEventService events) {
        this.runs = runs;
        this.events = events;
    }

    @GetMapping("/{runId}/events")
    public SseEmitter events(@PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        var run = runs.requireOwned(runId, SecurityContextHolder.getContext().getAuthentication().getName());
        return events.subscribe(run, lastEventId);
    }
}
