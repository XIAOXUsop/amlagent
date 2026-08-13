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
import org.springframework.util.DigestUtils;

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
    private static final String KEY_PREFIX = "legal:cache:v1:";

    private final ReRankingLegalSearcher delegate;
    private final StringRedisTemplate redisTemplate;
    private final MetricsRecorder metrics;
    private final ObjectMapper objectMapper;
    private final long ttlMinutes;

    public CachingLegalSearcher(ReRankingLegalSearcher delegate, StringRedisTemplate redisTemplate,
                                MetricsRecorder metrics, ObjectMapper objectMapper,
                                @Value("${aml.rag.cache-ttl-minutes:60}") long ttlMinutes) {
        this.delegate = delegate;
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.ttlMinutes = ttlMinutes;
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        String key = cacheKey(query, topK);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                metrics.ragCacheHit();
                return deserialize(cached);
            }
        } catch (Exception e) {
            log.warn("RAG 缓存读取失败，降级为直接检索：{}", e.getMessage());
        }

        List<LegalDoc> result = delegate.search(query, topK);
        metrics.ragCacheMiss();
        try {
            redisTemplate.opsForValue().set(key, serialize(result), Duration.ofMinutes(ttlMinutes));
        } catch (Exception e) {
            log.warn("RAG 缓存写入失败，忽略：{}", e.getMessage());
        }
        return result;
    }

    private String cacheKey(String query, int topK) {
        String hash = DigestUtils.md5DigestAsHex(query.getBytes(StandardCharsets.UTF_8));
        return KEY_PREFIX + hash + ":" + topK;
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
