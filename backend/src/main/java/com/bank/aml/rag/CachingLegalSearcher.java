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
 * RAG 检索缓存：装饰召回+精排搜索，用 Redis 缓存相同查询的检索结果（含各阶段分数 SearchHit），
 * 避免重复 embedding 与向量检索，降低 LLM 应用的计算与延迟成本。
 * <p>评测/门禁通过 {@link CacheMode#BYPASS_READ_WRITE} 显式绕过，不读写生产缓存。</p>
 */
@Component
@Primary
public class CachingLegalSearcher implements LegalDocumentSearcher {

    private static final Logger log = LoggerFactory.getLogger(CachingLegalSearcher.class);
    private static final String KEY_PREFIX = "legal:cache:v13:";
    private static final String SCORED_MARKER = "scored:";

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
        return cached(query, topK, "public", CacheMode.NORMAL, indexVersions.activeVersion(),
                () -> delegate.search(query, topK));
    }

    @Override
    public List<SearchHit> searchScored(RetrievalRequest request, int topK) {
        String contract = request.jurisdiction() + "|" + request.accessScopes().stream().sorted().toList()
                + "|" + request.asOfTime().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        String version = indexVersions.versionFor(request);
        return cachedScored(request.query(), topK, contract, request.cacheMode(), version,
                () -> delegate.searchScored(request, topK));
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        return searchScored(request, topK).stream().map(SearchHit::document).toList();
    }

    private List<LegalDoc> cached(String query, int topK, String contract, CacheMode mode, String version,
                                  java.util.function.Supplier<List<LegalDoc>> loader) {
        // 评测/门禁显式绕过缓存：不读不写，避免评测流量污染或命中生产缓存。
        if (mode == CacheMode.BYPASS_READ_WRITE) {
            List<LegalDoc> result = loader.get();
            metrics.ragCacheMiss();
            return result;
        }
        String key = cacheKey(query + "|" + contract, topK, version);
        String cached = read(key);
        if (cached != null) {
            try {
                return deserialize(cached, new TypeReference<List<LegalDoc>>() {});
            } catch (Exception e) {
                delete(key);
            }
        }
        List<LegalDoc> result = loader.get();
        metrics.ragCacheMiss();
        if (mode == CacheMode.NORMAL) write(key, serialize(result), query + "|" + contract, topK, version);
        return result;
    }

    private List<SearchHit> cachedScored(String query, int topK, String contract, CacheMode mode, String version,
                                         java.util.function.Supplier<List<SearchHit>> loader) {
        if (mode == CacheMode.BYPASS_READ_WRITE) {
            List<SearchHit> result = loader.get();
            metrics.ragCacheMiss();
            return result;
        }
        String key = cacheKeyScored(query + "|" + contract, topK, version);
        String body = read(key);
        if (body != null) {
            try {
                metrics.ragCacheHit();
                return deserialize(body, new TypeReference<List<SearchHit>>() {});
            } catch (Exception e) {
                log.warn("RAG SearchHit 缓存反序列化失败，删除坏 Key：{}", e.getMessage());
                delete(key);
            }
        }
        List<SearchHit> result = loader.get();
        metrics.ragCacheMiss();
        if (mode == CacheMode.NORMAL) {
            try {
                String identityAfterLoad = cacheKeyScored(query + "|" + contract, topK, version);
                if (key.equals(identityAfterLoad)) {
                    redisTemplate.opsForValue().set(key, serialize(result), Duration.ofMinutes(ttlMinutes));
                }
            } catch (Exception e) {
                log.warn("RAG SearchHit 缓存写入失败，忽略：{}", e.getMessage());
            }
        }
        return result;
    }

    private String read(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("RAG 缓存读取失败，降级为直接检索：{}", e.getMessage());
            return null;
        }
    }

    private void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {
            // 删除失败不影响重检
        }
    }

    private void write(String key, String json, String query, int topK, String version) {
        try {
            String identityAfterLoad = cacheKey(query, topK, version);
            if (key.equals(identityAfterLoad)) {
                redisTemplate.opsForValue().set(key, json, Duration.ofMinutes(ttlMinutes));
            } else {
                log.warn("RAG 检索期间管线身份发生变化，跳过缓存写入");
            }
        } catch (Exception e) {
            log.warn("RAG 缓存写入失败，忽略：{}", e.getMessage());
        }
    }

    // 缓存 key 版本化：语料/embedding/reranker 版本从配置读取，变更时自动失效
    private String cacheKey(String query, int topK, String version) {
        String hash = sha256(query);
        return KEY_PREFIX + version + ":" + embeddingModel + ":" + rerankerVersion + ":" + pipelineVersion
                + ":" + delegate.pipelineIdentity() + ":" + hash + ":" + topK;
    }

    private String cacheKeyScored(String query, int topK, String version) {
        return cacheKey(query, topK, version) + ":" + SCORED_MARKER;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("法规缓存序列化失败", e);
        }
    }

    private <T> T deserialize(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("法规缓存反序列化失败", e);
        }
    }
}