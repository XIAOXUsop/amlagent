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

    private final Counter caseTotal;
    private final Counter caseHoldTotal;
    private final Counter caseFailedTotal;
    private final Counter guardrailCorrectionTotal;
    private final Counter llmRequestTotal;
    private final Counter llmTokenTotal;
    private final Counter ragCacheHitTotal;
    private final Counter ragCacheMissTotal;
    private final Timer stageDuration;

    public MetricsRecorder(MeterRegistry registry) {
        this.caseTotal = registry.counter("aml_case_total");
        this.caseHoldTotal = registry.counter("aml_case_hold_total");
        this.caseFailedTotal = registry.counter("aml_case_failed_total");
        this.guardrailCorrectionTotal = registry.counter("aml_guardrail_correction_total");
        this.llmRequestTotal = registry.counter("aml_llm_request_total");
        this.llmTokenTotal = registry.counter("aml_llm_token_total");
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

    public void llmRequest() {
        llmRequestTotal.increment();
    }

    public void llmTokens(long tokens) {
        llmTokenTotal.increment(tokens);
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
