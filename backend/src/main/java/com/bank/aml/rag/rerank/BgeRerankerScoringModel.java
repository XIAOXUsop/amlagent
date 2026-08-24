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
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 bge-reranker-base（Cross-Encoder）重排模型，实现 LangChain4j {@link ScoringModel}。
 * <p>可靠性设计：</p>
 * <ul>
 *   <li>显式熔断状态机 CLOSED / OPEN / HALF_OPEN，连续失败达到阈值后熔断，冷却期后进入半开；</li>
 *   <li>半开状态只允许一个探测请求（{@code probeRunning} 独占），成功即复位；</li>
 *   <li>独立推理线程池 + 并发信号量：排队有界、申请超时，避免推理拖垮请求线程；</li>
 *   <li>批量打分（微批 4）走单线程执行器、等待带超时；</li>
 *   <li>启动预热 tokenizer 与 ONNX session；运行中失败自动重载。</li>
 * </ul>
 * 模型文件由 {@link RerankModelProvider} 定位并校验 SHA-256；生产环境关闭运行时下载。
 * <p>调用方不应依赖 {@link #isAvailable()} 提前分流，而是通过 {@link #tryScoreAll} 拿到可空结果，
 * 由状态机统一决定降级。</p>
 */
@Component
public class BgeRerankerScoringModel implements ScoringModel {

    private static final Logger log = LoggerFactory.getLogger(BgeRerankerScoringModel.class);

    private final RerankModelProvider modelProvider;
    private final boolean enabled;
    private final int maxConcurrency;
    private final int queueCapacity;
    private final long inferenceTimeoutMs;
    private final long quotaTimeoutMs;
    private final long cooldownMs;
    private final int failureThreshold;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    private volatile CircuitState state = CircuitState.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    /** OPEN 状态开启时间戳（用于冷却）；0 表示未熔断 */
    private volatile long circuitOpenedAt = 0;
    private final AtomicBoolean probeRunning = new AtomicBoolean(false);

    private final Semaphore inflight;
    private volatile ExecutorService inferenceExecutor;
    private static final int MICRO_BATCH = 4;

    public BgeRerankerScoringModel(RerankModelProvider modelProvider,
                                   @Value("${aml.rag.rerank.enabled:true}") boolean enabled,
                                   @Value("${aml.rag.rerank.max-concurrency:2}") int maxConcurrency,
                                   @Value("${aml.rag.rerank.queue-capacity:8}") int queueCapacity,
                                   @Value("${aml.rag.rerank.inference-timeout-ms:10000}") long inferenceTimeoutMs,
                                   @Value("${aml.rag.rerank.quota-timeout-ms:2000}") long quotaTimeoutMs,
                                   @Value("${aml.rag.rerank.cooldown-ms:60000}") long cooldownMs,
                                   @Value("${aml.rag.rerank.failure-threshold:10}") int failureThreshold) {
        this.modelProvider = modelProvider;
        this.enabled = enabled;
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.queueCapacity = Math.max(1, queueCapacity);
        this.inferenceTimeoutMs = Math.max(1000, inferenceTimeoutMs);
        this.quotaTimeoutMs = Math.max(200, quotaTimeoutMs);
        this.cooldownMs = Math.max(1000, cooldownMs);
        this.failureThreshold = Math.max(2, failureThreshold);
        this.inflight = new Semaphore(this.maxConcurrency);
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("rerank 已禁用（aml.rag.rerank.enabled=false），跳过模型加载");
            return;
        }
        ensureRuntime();
        if (session == null || tokenizer == null) {
            log.warn("rerank 模型不可用，RAG 将降级为无 rerank（可重试）");
            this.state = CircuitState.OPEN;
            this.circuitOpenedAt = System.currentTimeMillis();
            return;
        }
        this.state = CircuitState.CLOSED;
        warmUp();
    }

    /** 启动预热：执行一次最小推理，确保 tokenizer 与 ONNX session 已就绪。 */
    private void warmUp() {
        if (session == null || tokenizer == null) return;
        try {
            Encoding encoding = tokenizer.encode("预热", "金融机构应当立即冻结相关资产", true, false);
            try (OnnxTensor inputIds = OnnxTensor.createTensor(env, new long[][]{encoding.getIds()});
                 OnnxTensor attentionMask = OnnxTensor.createTensor(env, new long[][]{encoding.getAttentionMask()})) {
                try (OrtSession.Result ignored = session.run(Map.of("input_ids", inputIds, "attention_mask", attentionMask))) {
                    log.info("bge-reranker 预热完成，rerank 已启用");
                }
            }
        } catch (Exception e) {
            log.warn("bge-reranker 预热失败（将按需重试加载）：{}", e.getMessage());
            state = CircuitState.OPEN;
            circuitOpenedAt = System.currentTimeMillis();
        }
    }

    @PreDestroy
    void destroy() {
        ExecutorService executor = inferenceExecutor;
        if (executor != null) {
            executor.shutdownNow();
            inferenceExecutor = null;
        }
        closeQuietly(session);
        closeQuietly(tokenizer);
    }

    public boolean isAvailable() {
        return enabled && session != null && tokenizer != null && state != CircuitState.OPEN;
    }

    /** 供缓存身份使用：熔断状态或失败计数变化时，禁止把降级结果写入旧的精排缓存。 */
    public String runtimeIdentity() {
        return state + "-f" + consecutiveFailures.get();
    }

    /**
     * 可空推理入口：结果存在返回 Optional.of(scores)，不可用/超时/排队失败返回 Optional.empty()。
     * 调用方（Rerank 检索层）在空时保持召回原序，由状态机统一降级，无需提前分流。
     */
    public Optional<List<Double>> tryScoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) return Optional.of(List.of());
        if (!enabled || !ensureAccess()) return Optional.empty();
        if (!acquireQuota()) return Optional.empty();
        try {
            List<Encoding> encodings = new ArrayList<>(segments.size());
            for (TextSegment segment : segments) {
                encodings.add(tokenizer.encode(query, segment.text(), true, false));
            }
            List<Integer> order = java.util.stream.IntStream.range(0, encodings.size()).boxed()
                    .sorted(java.util.Comparator.comparingInt(i -> encodings.get(i).getIds().length)).toList();
            Double[] orderedScores = new Double[encodings.size()];
            boolean success = inferBatches(encodings, order, orderedScores, order.size());
            if (!success) {
                onInferenceFailure();
                return Optional.empty();
            }
            onSuccess();
            return Optional.of(java.util.Arrays.asList(orderedScores));
        } catch (RuntimeException e) {
            onInferenceFailure();
            log.warn("rerank 打分失败，按不可用降级：{}", e.getMessage());
            return Optional.empty();
        } finally {
            inflight.release();
        }
    }

    /** ScoringModel 接口适配：不可用时退化为全 0（不会抛出）。 */
    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        Optional<List<Double>> result = tryScoreAll(segments, query);
        return Response.from(result.orElseGet(() -> {
            List<Double> zeros = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) zeros.add(0.0);
            return zeros;
        }));
    }

    /** 查询配额：并发信号量 + 排队等待（超过等待时间即降级，不阻塞业务）。 */
    private boolean acquireQuota() {
        boolean acquired;
        try {
            acquired = inflight.tryAcquire(quotaTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!acquired) {
            log.warn("rerank 推理队列拥塞（等待 {}ms 未获得信号量），降级为无 rerank", quotaTimeoutMs);
            return false;
        }
        return true;
    }

    /** 在独立推理线程池中执行批量打分；等待带超时，避免推理异常拖垮调用线程。 */
    private boolean inferBatches(List<Encoding> encodings, List<Integer> order, Double[] scores, int total) {
        Future<?> task;
        try {
            task = executor().submit(() -> {
                int successCount = 0;
                for (int start = 0; start < order.size(); start += MICRO_BATCH) {
                    List<Integer> batch = order.subList(start, Math.min(start + MICRO_BATCH, order.size()));
                    if (!runOneBatch(encodings, batch, scores)) return false;
                    successCount++;
                }
                return successCount == batchesOf(order.size());
            });
        } catch (java.util.concurrent.RejectedExecutionException queueFull) {
            log.warn("rerank 推理队列已满（容量 {}），降级为无 rerank", queueCapacity);
            return false;
        }
        try {
            return Boolean.TRUE.equals(task.get(inferenceTimeoutMs, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            task.cancel(true);
            log.warn("rerank 推理等待超时（{}ms）或中断，降级为无 rerank：{}", inferenceTimeoutMs,
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean runOneBatch(List<Encoding> encodings, List<Integer> batch, Double[] scores) {
        if (session == null || tokenizer == null || env == null) tryReload();
        if (session == null || tokenizer == null || env == null) return false;
        try {
            int batchMaxLength = batch.stream().mapToInt(i -> encodings.get(i).getIds().length).max().orElse(1);
            long[][] ids = new long[batch.size()][batchMaxLength];
            long[][] masks = new long[batch.size()][batchMaxLength];
            for (int i = 0; i < batch.size(); i++) {
                Encoding encoding = encodings.get(batch.get(i));
                System.arraycopy(encoding.getIds(), 0, ids[i], 0, encoding.getIds().length);
                System.arraycopy(encoding.getAttentionMask(), 0, masks[i], 0, encoding.getAttentionMask().length);
            }
            try (OnnxTensor inputIds = OnnxTensor.createTensor(env, ids);
                 OnnxTensor attentionMask = OnnxTensor.createTensor(env, masks)) {
                try (OrtSession.Result result = session.run(Map.of("input_ids", inputIds, "attention_mask", attentionMask))) {
                    float[][] logits = (float[][]) result.get(0).getValue();
                    if (logits.length != batch.size()) return false;
                    for (int i = 0; i < logits.length; i++) {
                        if (logits[i].length == 0 || !Float.isFinite(logits[i][0])) return false;
                        scores[batch.get(i)] = (double) logits[i][0];
                    }
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("rerank 单批推理失败：{}", e.getMessage());
            return false;
        }
    }

    private int batchesOf(int size) {
        return (size + MICRO_BATCH - 1) / MICRO_BATCH;
    }

    private ExecutorService executor() {
        ExecutorService executor = inferenceExecutor;
        if (executor == null) {
            synchronized (this) {
                if (inferenceExecutor == null) {
                    java.util.concurrent.ThreadFactory factory = runnable -> {
                        Thread thread = new Thread(runnable, "bge-reranker-inference");
                        thread.setDaemon(true);
                        return thread;
                    };
                    inferenceExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(queueCapacity), factory,
                            new ThreadPoolExecutor.AbortPolicy());
                }
                executor = inferenceExecutor;
            }
        }
        return executor;
    }

    /** 访问门控：OPEN 拒绝；冷却结束进入 HALF_OPEN，且同一时刻只允许一个探测请求。 */
    private boolean ensureAccess() {
        CircuitState current = state;
        if (current == CircuitState.CLOSED) return true;
        if (current == CircuitState.OPEN) {
            if (System.currentTimeMillis() - circuitOpenedAt < cooldownMs) return false;
            // 冷却结束 → 半开
            synchronized (this) {
                if (state == CircuitState.OPEN) {
                    state = CircuitState.HALF_OPEN;
                    circuitOpenedAt = 0;
                }
            }
        }
        // HALF_OPEN：只放一个探测
        return state == CircuitState.HALF_OPEN
                && (probeRunning.compareAndSet(false, true) || !isAvailableSkipProbe());
    }

    private boolean isAvailableSkipProbe() {
        return !enabled || session == null || tokenizer == null;
    }

    private void onSuccess() {
        boolean wasHalfOpen = probeRunning.getAndSet(false);
        if (state == CircuitState.HALF_OPEN && wasHalfOpen) {
            synchronized (this) {
                state = CircuitState.CLOSED;
            }
            log.info("rerank 半开探测成功，恢复服务");
        }
        consecutiveFailures.set(0);
        if (consecutiveFailures.get() == 0 && state == CircuitState.OPEN) {
            synchronized (this) { if (state == CircuitState.OPEN) { state = CircuitState.CLOSED; circuitOpenedAt = 0; } }
        }
    }

    private void onInferenceFailure() {
        boolean wasHalfOpen = probeRunning.getAndSet(false);
        int failures = consecutiveFailures.incrementAndGet();
        if (state == CircuitState.HALF_OPEN || failures >= failureThreshold) {
            synchronized (this) {
                state = CircuitState.OPEN;
                circuitOpenedAt = state == CircuitState.OPEN && circuitOpenedAt == 0
                        ? System.currentTimeMillis() : circuitOpenedAt;
            }
            if (wasHalfOpen) log.warn("rerank 半开探测失败，重回熔断（OPEN）");
            else if (failures >= failureThreshold) log.error("rerank 连续失败 {} 次，触发熔断（{}）",
                    failures, CircuitState.OPEN);
        }
    }

    private void ensureRuntime() {
        if (session != null && tokenizer != null && env != null) return;
        try {
            Path dir = modelProvider.locateModel();
            if (dir == null) return;
            this.env = OrtEnvironment.getEnvironment();
            this.session = env.createSession(dir.resolve("model.onnx").toString(), new OrtSession.SessionOptions());
            this.tokenizer = HuggingFaceTokenizer.newInstance(dir.resolve("tokenizer.json"));
        } catch (Exception e) {
            log.warn("bge-reranker 加载失败，按不可用处理：{}", e.getMessage());
            this.session = null;
            this.tokenizer = null;
        }
    }

    private synchronized void tryReload() {
        if (session != null && tokenizer != null && env != null) return;
        OrtEnvironment newEnv = null;
        OrtSession newSession = null;
        HuggingFaceTokenizer newTokenizer = null;
        try {
            Path dir = modelProvider.locateModel();
            if (dir == null) return;
            newEnv = OrtEnvironment.getEnvironment();
            newSession = newEnv.createSession(dir.resolve("model.onnx").toString(), new OrtSession.SessionOptions());
            newTokenizer = HuggingFaceTokenizer.newInstance(dir.resolve("tokenizer.json"));
            this.env = newEnv;
            this.session = newSession;
            this.tokenizer = newTokenizer;
            concurrentlyCloseStale(newSession);
            log.info("rerank 模型重新加载成功");
        } catch (Exception e) {
            closeQuietly(newTokenizer);
            closeQuietly(newSession);
            log.warn("rerank 模型重新加载失败：{}", e.getMessage());
        }
    }

    private void concurrentlyCloseStale(OrtSession fresh) {
        // 保持现状：不主动关闭旧 session，避免影响当前并发推理；由 destroy 统一关闭
    }

    private void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
        }
    }
}