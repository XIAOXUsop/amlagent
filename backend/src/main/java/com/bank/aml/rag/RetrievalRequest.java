package com.bank.aml.rag;

import java.time.Instant;
import java.util.Set;

/** 检索输入契约：查询语义之外，显式携带时间、辖区和授权范围。 */
public record RetrievalRequest(
        String query,
        String topic,
        Instant asOfTime,
        String jurisdiction,
        Set<String> accessScopes,
        int topK,
    double minRelevance
) {
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
    }
}
