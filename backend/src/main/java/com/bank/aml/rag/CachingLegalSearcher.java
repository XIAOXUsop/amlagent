package com.bank.aml.rag;

import com.bank.aml.observability.MetricsRecorder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * RAG 检索缓存：装饰混合检索，用 Redis 缓存相同查询的检索结果，
 * 避免重复 embedding 与向量检索，降低 LLM 应用的计算与延迟成本。
 */
@Component
@Primary
public class CachingLegalSearcher implements LegalDocumentSearcher {

    private static final Logger log = LoggerFactory.getLogger(CachingLegalSearcher.class);
    private static final String KEY_PREFIX = "legal:cache:v11:";

    private final ReRankingLegalSearcher delegate;
    private final StringRedisTemplate redisTemplate;
    private final MetricsRecorder metrics;
    private final ObjectMapper objectMapper;
    private final long ttlMinutes;
    private final LegalIndexVersionProvider indexVersions;
    private final String embeddingModel;
    private final String rerankerVersion;
    private final String pipelineVersion;

    public CachingLegalSearcher(ReRankingLegalSearcher delegate, StringRedisTemplate redisTemplate,
                                MetricsRecorder metrics, ObjectMapper objectMapper,
                                @Value("${aml.rag.cache-ttl-minutes:60}") long ttlMinutes,
                                LegalIndexVersionProvider indexVersions,
                                @Value("${aml.rag.cache.embedding-model:all-minilm-l6-v2}") String embeddingModel,
                                @Value("${aml.rag.cache.reranker-version:bge-reranker-base}") String rerankerVersion) {
        this(delegate, redisTemplate, metrics, objectMapper, ttlMinutes, indexVersions,
                embeddingModel, rerankerVersion, "hybrid-rrf-v7");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CachingLegalSearcher(ReRankingLegalSearcher delegate, StringRedisTemplate redisTemplate,
                                MetricsRecorder metrics, ObjectMapper objectMapper,
                                @Value("${aml.rag.cache-ttl-minutes:60}") long ttlMinutes,
                                LegalIndexVersionProvider indexVersions,
                                @Value("${aml.rag.cache.embedding-model:all-minilm-l6-v2}") String embeddingModel,
                                @Value("${aml.rag.cache.reranker-version:bge-reranker-base}") String rerankerVersion,
                                @Value("${aml.rag.cache.pipeline-version:hybrid-rrf-v7}") String pipelineVersion) {
        this.delegate = delegate;
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.ttlMinutes = ttlMinutes;
        this.indexVersions = indexVersions;
        this.embeddingModel = embeddingModel;
        this.rerankerVersion = rerankerVersion;
        this.pipelineVersion = pipelineVersion;
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        return cached(query, topK, "public", () -> delegate.search(query, topK));
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        String contract = request.jurisdiction() + "|" + request.accessScopes().stream().sorted().toList()
                + "|" + request.asOfTime().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return cached(request.query(), topK, contract, () -> delegate.search(request, topK));
    }

    private List<LegalDoc> cached(String query, int topK, String contract,
                                  java.util.function.Supplier<List<LegalDoc>> loader) {
        String key = cacheKey(query + "|" + contract, topK);
        String cached = null;
        try {
            cached = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("RAG 缓存读取失败，降级为直接检索：{}", e.getMessage());
        }
        if (cached != null) {
            try {
                metrics.ragCacheHit();
                return deserialize(cached);
            } catch (Exception e) {
                // 坏缓存：删除坏 Key 后重检，避免后续请求反复命中损坏数据
                log.warn("RAG 缓存反序列化失败，删除坏 Key 并降级重检：{}", e.getMessage());
                try {
                    redisTemplate.delete(key);
                } catch (Exception ignored) {
                    // 删除失败不影响重检
                }
            }
        }

        List<LegalDoc> result = loader.get();
        metrics.ragCacheMiss();
        try {
            String identityAfterLoad = cacheKey(query + "|" + contract, topK);
            if (key.equals(identityAfterLoad)) {
                redisTemplate.opsForValue().set(key, serialize(result), Duration.ofMinutes(ttlMinutes));
            } else {
                log.warn("RAG 检索期间管线身份发生变化，跳过缓存写入");
            }
        } catch (Exception e) {
            log.warn("RAG 缓存写入失败，忽略：{}", e.getMessage());
        }
        return result;
    }

    // 缓存 key 版本化：语料/embedding/reranker 版本从配置读取，变更时自动失效
    private String cacheKey(String query, int topK) {
        String hash = sha256(query);
        return KEY_PREFIX + indexVersions.activeVersion() + ":" + embeddingModel + ":" + rerankerVersion + ":" + pipelineVersion
                + ":" + delegate.pipelineIdentity() + ":" + hash + ":" + topK;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private String serialize(List<LegalDoc> docs) {
        try {
            return objectMapper.writeValueAsString(docs);
        } catch (Exception e) {
            throw new RuntimeException("法规缓存序列化失败", e);
        }
    }

    private List<LegalDoc> deserialize(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<LegalDoc>>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("法规缓存反序列化失败", e);
        }
    }
}
