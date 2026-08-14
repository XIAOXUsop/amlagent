package com.bank.aml.controller;

import com.bank.aml.dto.CaseDto;
import com.bank.aml.messaging.DeadLetterService;
import com.bank.aml.messaging.WorkflowCommandService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 可靠任务队列相关接口。
 */
@RestController
@RequestMapping("/api/queues")
@PreAuthorize("hasRole('ADMIN')")
public class QueueController {

    private final DeadLetterService deadLetterService;
    private final WorkflowCommandService workflowCommandService;

    public QueueController(DeadLetterService deadLetterService, WorkflowCommandService workflowCommandService) {
        this.deadLetterService = deadLetterService;
        this.workflowCommandService = workflowCommandService;
    }

    /** 死信队列消息 */
    @GetMapping("/dead")
    public List<Map<String, String>> dead() {
        return deadLetterService.list();
    }

    /** 死信重放（仅 ADMIN）：重置工单并通过 Outbox 重新入队，产生新 executionVersion */
    @PostMapping("/dead/{caseId}/replay")
    public CaseDto replay(@PathVariable Long caseId) {
        return CaseDto.from(workflowCommandService.replayDead(caseId));
    }
}
