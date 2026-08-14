package com.bank.aml.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 业务指标埋点（Prometheus 格式，经 /actuator/prometheus 暴露）。
 */
@Component
public class MetricsRecorder {

    private final MeterRegistry registry;
    private final Counter caseTotal;
    private final Counter caseHoldTotal;
    private final Counter caseFailedTotal;
    private final Counter guardrailCorrectionTotal;
    private final Counter ragCacheHitTotal;
    private final Counter ragCacheMissTotal;
    private final Timer stageDuration;

    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
        this.caseTotal = registry.counter("aml_case_total");
        this.caseHoldTotal = registry.counter("aml_case_hold_total");
        this.caseFailedTotal = registry.counter("aml_case_failed_total");
        this.guardrailCorrectionTotal = registry.counter("aml_guardrail_correction_total");
        this.ragCacheHitTotal = registry.counter("aml_rag_cache_hit_total");
        this.ragCacheMissTotal = registry.counter("aml_rag_cache_miss_total");
        this.stageDuration = registry.timer("aml_stage_duration_seconds");
    }

    public void caseCreated() {
        caseTotal.increment();
    }

    public void caseHold() {
        caseHoldTotal.increment();
    }

    public void caseFailed() {
        caseFailedTotal.increment();
    }

    public void guardrailCorrection() {
        guardrailCorrectionTotal.increment();
    }

    /** 模型请求数（按 purpose 区分 main_agent / summary） */
    public void llmRequest(String purpose) {
        registry.counter("aml_llm_request_total", "purpose", purpose).increment();
    }

    /** 模型 Token 数（按 purpose 区分 main_agent / summary） */
    public void llmTokens(String purpose, long tokens) {
        registry.counter("aml_llm_token_total", "purpose", purpose).increment(tokens);
    }

    /** 模型调用错误（按 purpose 区分） */
    public void llmError(String purpose) {
        registry.counter("aml_llm_error_total", "purpose", purpose).increment();
    }

    public void ragCacheHit() {
        ragCacheHitTotal.increment();
    }

    public void ragCacheMiss() {
        ragCacheMissTotal.increment();
    }

    public void recordStageDuration(long durationMs) {
        stageDuration.record(Duration.ofMillis(durationMs));
    }
}
