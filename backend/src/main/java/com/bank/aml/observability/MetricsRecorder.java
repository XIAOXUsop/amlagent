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

    /** 模型请求数（按 provider/model/purpose 标签区分） */
    public void llmRequest(ModelInvocationTags tags) {
        registry.counter("aml_llm_request_total", "provider", tags.provider(),
                "model", tags.model(), "purpose", tags.purpose()).increment();
    }

    /** 模型 Token 数（按 provider/model/purpose 与 input/output 类型区分） */
    public void llmTokens(ModelInvocationTags tags, long inputTokens, long outputTokens) {
        registry.counter("aml_llm_token_total", "provider", tags.provider(),
                "model", tags.model(), "purpose", tags.purpose(), "type", "input").increment(inputTokens);
        registry.counter("aml_llm_token_total", "provider", tags.provider(),
                "model", tags.model(), "purpose", tags.purpose(), "type", "output").increment(outputTokens);
    }

    /** 模型调用错误（按 provider/model/purpose 标签区分） */
    public void llmError(ModelInvocationTags tags) {
        registry.counter("aml_llm_error_total", "provider", tags.provider(),
                "model", tags.model(), "purpose", tags.purpose()).increment();
    }

    /** 模型调用延迟（按 provider/model/purpose 标签区分，Timer 提供 P50/P95） */
    public void llmDuration(ModelInvocationTags tags, long durationMs) {
        registry.timer("aml_llm_duration_seconds", "provider", tags.provider(),
                "model", tags.model(), "purpose", tags.purpose()).record(Duration.ofMillis(durationMs));
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

    // ---- 可靠队列健康指标（Redis Streams 消费者）----

    /** 消费者容器异常/停摆事件计数（健康告警依据） */
    public void queueConsumerError() {
        registry.counter("aml_queue_consumer_error_total").increment();
    }

    /** 消费者容器处于停止状态（不可用）的事件计数 */
    public void queueConsumerDown() {
        registry.counter("aml_queue_consumer_down_total").increment();
    }

    /** 记录当前消费 lag（未消费消息数，Gauge 便于告警阈值判断） */
    public void queueLag(long lag) {
        registry.gauge("aml_queue_lag", lag);
    }
}
