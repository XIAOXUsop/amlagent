package com.bank.aml.rag;

import com.bank.aml.config.RagProperties;
import com.bank.aml.observability.MetricsRecorder;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 查询向量 LRU 缓存：对同一（规范化查询, 索引版本）复用查询 embedding，降低推理成本。
 * <p>
 * key 为查询的不可逆 SHA-256 指纹 + 索引版本，不存储原始查询文本。
 * </p>
 */
@Component
public class QueryEmbeddingCache {

    private final int capacity;

    private final Map<String, Embedding> cache;

    public QueryEmbeddingCache(RagProperties properties) {
        this.capacity = properties.getRetrieval().getQueryEmbeddingCacheCapacity();
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Embedding> eldest) {
                return size() > capacity;
            }
        });
    }

    public Embedding getOrEmbed(String query, String version, EmbeddingModel model, MetricsRecorder metrics) {
        String key = fingerprint(query) + "|" + version;
        Embedding cached = cache.get(key);
        if (cached != null) {
            if (metrics != null)
                metrics.ragEmbeddingReuse();
            return cached;
        }
        Embedding embedding = model.embed(query).content();
        cache.put(key, embedding);
        if (metrics != null)
            metrics.ragEmbeddingCompute();
        return embedding;
    }

    /** 不可逆查询指纹：仅用于缓存键与审计，不记录原始查询。 */
    public static String fingerprint(String query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((query == null ? "" : query).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

}
