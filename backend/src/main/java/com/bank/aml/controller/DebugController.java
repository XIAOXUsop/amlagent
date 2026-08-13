package com.bank.aml.controller;

import com.bank.aml.common.fault.FaultInjector;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 调试接口（仅 ADMIN）：故障注入开关，用于可靠性演示。
 */
@RestController
@RequestMapping("/api/debug")
@PreAuthorize("hasRole('ADMIN')")
public class DebugController {

    private final FaultInjector faultInjector;

    public DebugController(FaultInjector faultInjector) {
        this.faultInjector = faultInjector;
    }

    /** 开启/关闭故障注入 */
    @PostMapping("/fault")
    public Map<String, Object> setFault(@RequestParam(defaultValue = "true") boolean enabled,
                                        @RequestParam(defaultValue = "3") int failCount) {
        if (enabled) {
            faultInjector.enable(failCount);
        } else {
            faultInjector.disable();
        }
        return faultInjector.status();
    }

    /** 查看注入状态 */
    @GetMapping("/fault")
    public Map<String, Object> faultStatus() {
        return faultInjector.status();
    }
}
