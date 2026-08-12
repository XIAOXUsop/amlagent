package com.bank.aml.controller;

import com.bank.aml.messaging.DeadLetterService;
import org.springframework.web.bind.annotation.GetMapping;
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

    public QueueController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    /** 死信队列消息 */
    @GetMapping("/dead")
    public List<Map<String, String>> dead() {
        return deadLetterService.list();
    }
}
