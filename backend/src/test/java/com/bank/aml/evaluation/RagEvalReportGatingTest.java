package com.bank.aml.evaluation;

import com.bank.aml.rag.RetrievalPipeline;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-04（v4 计划 §4.4 / RF-29）：A/B 评测必须记录实际执行管线； 声称包含重排的管线一次 rerank 都没发生 → 环境失败，不冒充"四路比较"。
 * 本测试锁定 RagEvalReport 的门禁语义（构造与字段语义；全链路 A/B 由集成环境验证）。
 */
class RagEvalReportGatingTest {

    @Test
    void rerankGatingFieldsExistWithCompatConstructor() {
        // 兼容构造：未记录实际管线 → rerankInvocations=-1、environmentFailure=false（存量调用不破坏）
        RagEvaluator.RagEvalReport legacy = new RagEvaluator.RagEvalReport(18, 100.0, 100.0, 95.6, 96.7, 100.0, 100.0,
                671.0, 300.0, 671.0, 700.0, 100.0, 120.0, 150.0,
                new RagEvaluator.SegmentedLatency(Map.of("rerank", 200.0)), "v1", "hash", "PENDING_DOMAIN_REVIEW",
                List.of(), "PRODUCTION");
        assertThat(legacy.rerankInvocations()).isEqualTo(-1);
        assertThat(legacy.environmentFailure()).isFalse();
    }

    @Test
    void rerankPipelineWithoutInvocationIsEnvironmentFailure() {
        // HYBRID_RERANK + rerankInvocations=0 → environmentFailure（RF-29：不能输出零召回后算成功）
        RagEvaluator.RagEvalReport report = new RagEvaluator.RagEvalReport(18, 0.0, 0.0, 0.0, 0.0, 100.0, 100.0, 10.0,
                8.0, 10.0, 12.0, 9.0, 11.0, 13.0, new RagEvaluator.SegmentedLatency(Map.of()), "v1", "hash",
                "PENDING_DOMAIN_REVIEW", List.of(), RetrievalPipeline.HYBRID_RERANK.name(), 0, true);
        assertThat(report.environmentFailure()).isTrue();
        assertThat(report.rerankInvocations()).isZero();
    }

    @Test
    void rerankPipelineWithInvocationIsNotEnvironmentFailure() {
        RagEvaluator.RagEvalReport report = new RagEvaluator.RagEvalReport(18, 100.0, 100.0, 95.6, 96.7, 100.0, 100.0,
                671.0, 300.0, 671.0, 700.0, 100.0, 120.0, 150.0,
                new RagEvaluator.SegmentedLatency(Map.of("rerank", 200.0)), "v1", "hash", "PENDING_DOMAIN_REVIEW",
                List.of(), RetrievalPipeline.HYBRID_RERANK.name(), 18, false);
        assertThat(report.environmentFailure()).isFalse();
        assertThat(report.rerankInvocations()).isEqualTo(18);
    }

    @Test
    void nonRerankPipelineIgnoresGating() {
        // 非 HYBRID_RERANK 管线：即使 rerankInvocations=0 也不是环境失败（管线本来就不含重排）
        RagEvaluator.RagEvalReport report = new RagEvaluator.RagEvalReport(18, 93.3, 93.3, 81.1, 84.2, 100.0, 100.0,
                135.0, 100.0, 135.0, 140.0, 50.0, 60.0, 70.0, new RagEvaluator.SegmentedLatency(Map.of()), "v1", "hash",
                "PENDING_DOMAIN_REVIEW", List.of(), RetrievalPipeline.HYBRID.name(), 0, false);
        assertThat(report.environmentFailure()).isFalse();
    }

}
