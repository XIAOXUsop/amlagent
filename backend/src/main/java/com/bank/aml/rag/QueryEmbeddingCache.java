package com.bank.aml.rag;

import com.bank.aml.observability.MetricsRecorder;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询向量 LRU 缓存：对同一（规范化查询, 索引版本）复用查询 embedding，降低推理成本。
 * <p>key 为查询的不可逆 SHA-256 指纹 + 索引版本，不存储原始查询文本。</p>
 */
@Component
public class QueryEmbeddingCache {
    private static final int CAPACITY = 512;

    private final Map<String, Embedding> cache = Collections.synchronizedMap(new LinkedHashMap<>(CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Embedding> eldest) {
            return size() > CAPACITY;
        }
    });

    public Embedding getOrEmbed(String query, String version, EmbeddingModel model, MetricsRecorder metrics) {
        String key = fingerprint(query) + "|" + version;
        Embedding cached = cache.get(key);
        if (cached != null) {
            if (metrics != null) metrics.ragEmbeddingReuse();
            return cached;
        }
        Embedding embedding = model.embed(query).content();
        cache.put(key, embedding);
        if (metrics != null) metrics.ragEmbeddingCompute();
        return embedding;
    }

    /** 不可逆查询指纹：仅用于缓存键与审计，不记录原始查询。 */
    public static String fingerprint(String query) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((query == null ? "" : query).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}