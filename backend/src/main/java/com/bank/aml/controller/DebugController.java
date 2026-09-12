package com.bank.aml.controller;

import com.bank.aml.audit.AuditService;
import com.bank.aml.common.fault.FaultInjector;
import com.bank.aml.security.RedisRateLimiter;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调试接口（仅 ADMIN）：故障注入开关，用于可靠性演示。 仅在非生产 Profile 注册，避免生产环境暴露故障注入能力。
 */
@RestController
@RequestMapping("/api/debug")
@PreAuthorize("hasRole('ADMIN')")
@Profile("!prod")
public class DebugController {

    private final FaultInjector faultInjector;

    private final AuditService audit;

    private final RedisRateLimiter rateLimiter;

    public DebugController(FaultInjector faultInjector, AuditService audit, RedisRateLimiter rateLimiter) {
        this.faultInjector = faultInjector;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
    }

    /** 开启/关闭故障注入（每操作者 60 秒内最多 10 次） */
    @PostMapping("/fault")
    public FaultInjector.FaultStatus setFault(@RequestParam(defaultValue = "true") boolean enabled,
            @RequestParam(defaultValue = "3") int failCount) {
        String actor = SecurityContextHolder.getContext().getAuthentication().getName();
        rateLimiter.checkLimit("debug-fault:" + actor, 10, 60);
        if (enabled) {
            faultInjector.enable(failCount);
        }
        else {
            faultInjector.disable();
        }
        audit.record(actor, "DEBUG_FAULT_INJECTION", "WORKFLOW", null, "SUCCESS",
                "enabled=" + enabled + ",failCount=" + failCount, null);
        return faultInjector.status();
    }

    /** 查看注入状态 */
    @GetMapping("/fault")
    public FaultInjector.FaultStatus faultStatus() {
        return faultInjector.status();
    }

}
