package com.bank.aml.controller;

import com.bank.aml.common.enums.CaseStatus;
import com.bank.aml.datasource.entity.CaseEntity;
import com.bank.aml.datasource.repository.CaseRepository;
import com.bank.aml.dto.CaseDto;
import com.bank.aml.messaging.DeadLetterService;
import com.bank.aml.service.DueDiligenceService;
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
public class QueueController {

    private final DeadLetterService deadLetterService;
    private final CaseRepository caseRepository;
    private final DueDiligenceService dueDiligenceService;

    public QueueController(DeadLetterService deadLetterService, CaseRepository caseRepository,
                           DueDiligenceService dueDiligenceService) {
        this.deadLetterService = deadLetterService;
        this.caseRepository = caseRepository;
        this.dueDiligenceService = dueDiligenceService;
    }

    /** 死信队列消息 */
    @GetMapping("/dead")
    public List<Map<String, String>> dead() {
        return deadLetterService.list();
    }

    /** 死信重放（仅 ADMIN）：重置工单并重新入队，产生新 executionVersion */
    @PostMapping("/dead/{caseId}/replay")
    @PreAuthorize("hasRole('ADMIN')")
    public CaseDto replay(@PathVariable Long caseId) {
        CaseEntity c = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在：" + caseId));
        c.setStatus(CaseStatus.PENDING);
        c.setRetryCount(0);
        c.setNextRetryAt(null);
        c.setLockedBy(null);
        c.setLockedAt(null);
        c.setHeartbeatAt(null);
        c.setFailureCode(null);
        c.setFailureMessage(null);
        caseRepository.save(c);
        dueDiligenceService.enqueue(caseId);
        return CaseDto.from(c);
    }
}
