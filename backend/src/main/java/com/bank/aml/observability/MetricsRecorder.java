package com.bank.aml.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Counter llmFallbackTotal;
    /** 可变的消费 lag 值：Micrometer Gauge 通过强引用持有本对象实时读取，避免每次传新值被注册表冻结 */
    private final AtomicLong queueLagHolder;

    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
        this.caseTotal = registry.counter("aml_case_total");
        this.caseHoldTotal = registry.counter("aml_case_hold_total");
        this.caseFailedTotal = registry.counter("aml_case_failed_total");
        this.guardrailCorrectionTotal = registry.counter("aml_guardrail_correction_total");
        this.ragCacheHitTotal = registry.counter("aml_rag_cache_hit_total");
        this.ragCacheMissTotal = registry.counter("aml_rag_cache_miss_total");
        this.llmFallbackTotal = registry.counter("aml_case_llm_fallback_total");
        this.queueLagHolder = new AtomicLong(0);
        // Gauge 持有引用而非值：后续 queueLag() 只更新 AtomicLong，指标始终读取最新值
        registry.gauge("aml_queue_lag", queueLagHolder, AtomicLong::doubleValue);
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

    public void ragRetrieval(String status, long durationMs, int hitCount) {
        registry.counter("aml_rag_retrieval_total", "status", status).increment();
        registry.timer("aml_rag_retrieval_duration_seconds", "status", status)
                .record(Duration.ofMillis(durationMs));
        registry.summary("aml_rag_returned_hits", "status", status).record(hitCount);
    }

    /** 零命中：无任何证据命中（与总检索量配合计算 zero-hit rate） */
    public void ragZeroHit() {
        registry.counter("aml_rag_zero_hit_total").increment();
    }

    /** 拒答：未给出支持证据的检索（用于拒答率观测） */
    public void ragAbstention(String status) {
        registry.counter("aml_rag_abstention_total", "status", safeMetricTag(status, "UNKNOWN")).increment();
    }

    /** ACL 过滤条数（命中但访问范围不足被剔除） */
    public void ragAclFiltered() {
        registry.counter("aml_rag_acl_filtered_total").increment();
    }

    /** 失效法规过滤条数（命中但超出生效窗口被剔除） */
    public void ragExpiredFiltered() {
        registry.counter("aml_rag_expired_filtered_total").increment();
    }

    /** Reranker 降级/熔断次数 */
    public void ragRerankerFallback() {
        registry.counter("aml_rag_reranker_fallback_total").increment();
    }

    /** 候选与 active 命中的重合比例（0..1），用于发布门禁对比观测 */
    public void ragCandidateActiveOverlap(double overlap) {
        registry.gauge("aml_rag_candidate_active_overlap", Math.max(0.0, Math.min(1.0, overlap)));
    }

    /** 评测冷/热延迟（stage=cold|warm） */
    public void ragLatency(String stage, double ms) {
        registry.timer("aml_rag_latency_ms", "stage", safeMetricTag(stage, "unknown"))
                .record(Duration.ofMillis((long) Math.max(0, ms)));
    }

    /** embedding 复用/重算计数（rate=reuse/(reuse+compute)） */
    public void ragEmbeddingReuse() {
        registry.counter("aml_rag_embedding_reuse_total").increment();
    }

    public void ragEmbeddingCompute() {
        registry.counter("aml_rag_embedding_compute_total").increment();
    }

    public void ragIndexBuild(String status, long durationMs, int segmentCount) {
        registry.counter("aml_rag_index_build_total", "status", status).increment();
        registry.timer("aml_rag_index_build_duration_seconds", "status", status)
                .record(Duration.ofMillis(durationMs));
        if (segmentCount > 0) registry.summary("aml_rag_index_segments").record(segmentCount);
    }

    public void recordStageDuration(String stage, long durationMs) {
        registry.timer("aml_stage_duration_seconds", "stage", stage).record(Duration.ofMillis(durationMs));
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
        queueLagHolder.set(lag);
    }

    /** Agent 调用失败后由规则引擎降级出报告的次数（区分正常成功与降级成功，供运维感知 LLM 故障） */
    public void caseLlmFallback() {
        llmFallbackTotal.increment();
    }

    /** AI 小助运行结果；status/intent 均为有限枚举，禁止放入客户或会话标识。 */
    public void assistantRun(String status, String intent, long durationMs) {
        String safeStatus = safeMetricTag(status, "UNKNOWN");
        String safeIntent = safeMetricTag(intent, "UNKNOWN");
        registry.counter("aml_assistant_run_total", "status", safeStatus, "intent", safeIntent).increment();
        registry.timer("aml_assistant_run_duration_seconds", "status", safeStatus)
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    public void assistantOutputBlocked(String reason) {
        registry.counter("aml_assistant_output_blocked_total", "reason", safeMetricTag(reason, "UNKNOWN")).increment();
    }

    private static String safeMetricTag(String value, String fallback) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) return fallback;
        return value;
    }
}
