package com.bank.aml.rag;

import java.time.Instant;
import java.util.Set;

/** 检索输入契约：查询语义之外，显式携带时间、辖区和授权范围，以及服务端内部目标索引与缓存策略。 */
public record RetrievalRequest(
        String query,
        String topic,
        Instant asOfTime,
        String jurisdiction,
        Set<String> accessScopes,
        int topK,
        double minRelevance,
        RetrievalTarget target,
        String specificVersion,
        CacheMode cacheMode
) {
    /** 生产检索便捷构造：默认 ACTIVE 目标、NORMAL 缓存。 */
    public RetrievalRequest(String query, String topic, Instant asOfTime, String jurisdiction,
                            Set<String> accessScopes, int topK, double minRelevance) {
        this(query, topic, asOfTime, jurisdiction, accessScopes, topK, minRelevance,
                RetrievalTarget.ACTIVE, null, CacheMode.NORMAL);
    }

    public RetrievalRequest {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("检索 query 不能为空");
        query = query.trim();
        if (query.length() > 512) throw new IllegalArgumentException("检索 query 过长");
        topic = topic == null ? "" : topic.trim();
        if (topic.length() > 128) throw new IllegalArgumentException("检索 topic 过长");
        asOfTime = asOfTime == null ? Instant.now() : asOfTime;
        jurisdiction = jurisdiction == null || jurisdiction.isBlank() ? "CN" : jurisdiction;
        if (!jurisdiction.matches("[A-Z]{2,8}(?:-[A-Z0-9]{1,8})?")) {
            throw new IllegalArgumentException("检索 jurisdiction 非法");
        }
        try {
            accessScopes = accessScopes == null ? Set.of() : Set.copyOf(accessScopes);
        } catch (NullPointerException invalidScope) {
            throw new IllegalArgumentException("检索访问范围包含空值", invalidScope);
        }
        if (accessScopes.isEmpty()) throw new IllegalArgumentException("检索访问范围不能为空");
        if (accessScopes.size() > 16 || accessScopes.stream().anyMatch(scope ->
                scope == null || !scope.matches("[A-Z][A-Z0-9_]{1,63}"))) {
            throw new IllegalArgumentException("检索访问范围非法");
        }
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("topK 必须在 1..20");
        if (minRelevance < 0 || minRelevance > 1) throw new IllegalArgumentException("minRelevance 必须在 0..1");
        target = target == null ? RetrievalTarget.ACTIVE : target;
        cacheMode = cacheMode == null ? CacheMode.NORMAL : cacheMode;
        if (specificVersion != null && !specificVersion.isBlank()) {
            if (!specificVersion.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("检索 specificVersion 必须是 64 位十六进制的索引版本");
            }
            specificVersion = specificVersion.toLowerCase();
        }
        if (target == RetrievalTarget.SPECIFIC_VERSION) {
            if (specificVersion == null || specificVersion.isBlank()) {
                throw new IllegalArgumentException("SPECIFIC_VERSION 检索必须携带 specificVersion");
            }
        } else {
            specificVersion = null;
        }
    }
}