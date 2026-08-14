package com.bank.aml.rag.rerank;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本地 bge-reranker-base（Cross-Encoder）重排模型：实现 LangChain4j {@link ScoringModel}。
 * <p>对 query-doc 对做交叉编码打分，用于召回结果精排。
 * 支持 {@code aml.rag.rerank.enabled=false} 跳过模型加载；模型不可用时降级为无 rerank。
 * ONNX 张量与 Session 使用 try-with-resources / {@link PreDestroy} 释放，避免 native 内存泄漏。
 */
@Component
public class BgeRerankerScoringModel implements ScoringModel {

    private static final Logger log = LoggerFactory.getLogger(BgeRerankerScoringModel.class);

    private final RerankModelProvider modelProvider;
    private final boolean enabled;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private volatile boolean available = false;
    /** 连续失败计数（熔断：连续失败超过阈值则临时降级为无 rerank） */
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int FAILURE_THRESHOLD = 10;
    /** 熔断打开时间戳（0 表示未熔断）；冷却后进入半开探测 */
    private volatile long circuitOpenedAt = 0;
    private static final long COOLDOWN_MS = 60_000;

    public BgeRerankerScoringModel(RerankModelProvider modelProvider,
                                   @Value("${aml.rag.rerank.enabled:true}") boolean enabled) {
        this.modelProvider = modelProvider;
        this.enabled = enabled;
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("rerank 已禁用（aml.rag.rerank.enabled=false），跳过模型加载");
            return;
        }
        try {
            Path dir = modelProvider.locateModel();
            if (dir == null) {
                log.warn("rerank 模型不可用，RAG 将降级为无 rerank（可重试）");
                this.circuitOpenedAt = System.currentTimeMillis();
                return;
            }
            this.env = OrtEnvironment.getEnvironment();
            this.session = env.createSession(dir.resolve("model.onnx").toString(), new OrtSession.SessionOptions());
            this.tokenizer = HuggingFaceTokenizer.newInstance(dir.resolve("tokenizer.json"));
            this.available = true;
            log.info("bge-reranker 模型加载成功，rerank 已启用");
        } catch (Exception e) {
            log.warn("bge-reranker 加载失败，降级为无 rerank（可重试）：{}", e.getMessage());
            this.available = false;
            this.circuitOpenedAt = System.currentTimeMillis();
        }
    }

    @PreDestroy
    void destroy() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("ONNX Session 关闭失败：{}", e.getMessage());
            }
        }
        if (tokenizer != null) {
            tokenizer.close();
        }
    }

    public boolean isAvailable() {
        return available;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        List<Double> scores = new ArrayList<>();
        for (TextSegment segment : segments) {
            scores.add(crossScore(query, segment.text()));
        }
        return Response.from(scores);
    }

    private double crossScore(String query, String document) {
        if (!available) {
            // 熔断冷却期：直接降级为无 rerank
            if (circuitOpenedAt == 0 || System.currentTimeMillis() - circuitOpenedAt < COOLDOWN_MS) {
                return 0.0;
            }
            // 半开探测：冷却结束后允许一次探测，成功（未抛异常且返回有限值）则恢复
            synchronized (this) {
                if (!available && System.currentTimeMillis() - circuitOpenedAt >= COOLDOWN_MS) {
                    ScoreResult probe = tryScore(query, document);
                    if (probe.success()) {
                        available = true;
                        consecutiveFailures.set(0);
                        circuitOpenedAt = 0;
                        log.info("rerank 半开探测成功，恢复 rerank 服务");
                    } else {
                        circuitOpenedAt = System.currentTimeMillis();
                        log.warn("rerank 半开探测失败，继续熔断");
                    }
                    return probe.score();
                }
            }
            return 0.0;
        }
        return tryScore(query, document).score();
    }

    private ScoreResult tryScore(String query, String document) {
        if (session == null || tokenizer == null) {
            tryReload(); // 初始加载失败后，冷却结束尝试重新加载
        }
        if (session == null || tokenizer == null) {
            return new ScoreResult(false, 0.0);
        }
        try {
            Encoding encoding = tokenizer.encode(query, document, true, false);
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();

            try (OnnxTensor inputIds = OnnxTensor.createTensor(env, new long[][]{ids});
                 OnnxTensor attentionMask = OnnxTensor.createTensor(env, new long[][]{mask})) {
                Map<String, OnnxTensor> inputs = Map.of("input_ids", inputIds, "attention_mask", attentionMask);
                try (OrtSession.Result result = session.run(inputs)) {
                    float[][] logits = (float[][]) result.get(0).getValue();
                    consecutiveFailures.set(0); // 成功重置
                    double score = logits[0][0];
                    return new ScoreResult(Double.isFinite(score), score);
                }
            }
        } catch (Exception e) {
            // 连续失败熔断：超过阈值临时降级为无 rerank，冷却后自动半开恢复
            if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
                available = false;
                circuitOpenedAt = System.currentTimeMillis();
                log.error("rerank 连续失败 {} 次，触发熔断降级为无 rerank", FAILURE_THRESHOLD);
            } else {
                log.warn("rerank 打分失败，返回 0：{}", e.getMessage());
            }
            return new ScoreResult(false, 0.0);
        }
    }

    private synchronized void tryReload() {
        if (session != null) {
            return;
        }
        try {
            Path dir = modelProvider.locateModel();
            if (dir == null) {
                return;
            }
            this.env = OrtEnvironment.getEnvironment();
            this.session = env.createSession(dir.resolve("model.onnx").toString(), new OrtSession.SessionOptions());
            this.tokenizer = HuggingFaceTokenizer.newInstance(dir.resolve("tokenizer.json"));
            log.info("rerank 模型重新加载成功");
        } catch (Exception e) {
            log.warn("rerank 模型重新加载失败：{}", e.getMessage());
        }
    }

    private record ScoreResult(boolean success, double score) {
    }
}
