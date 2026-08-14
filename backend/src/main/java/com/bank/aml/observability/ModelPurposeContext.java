package com.bank.aml.observability;

import org.springframework.stereotype.Component;

/**
 * 模型调用目的上下文（ThreadLocal）：区分主 Agent（main_agent）与可选摘要（summary），
 * 使 Token/请求指标能按 purpose 维度拆分成本。
 */
@Component
public class ModelPurposeContext {

    private final ThreadLocal<String> purpose = new ThreadLocal<>();

    public void set(String value) {
        purpose.set(value);
    }

    public String get() {
        String value = purpose.get();
        return value == null ? "unknown" : value;
    }

    public void clear() {
        purpose.remove();
    }
}
