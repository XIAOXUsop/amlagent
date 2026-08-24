package com.bank.aml.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.bank.aml.config.RagProperties;

import javax.sql.DataSource;
import java.util.List;

/**
 * 基于 PostgreSQL 的关键词/全文召回（ILIKE 精确匹配），供混合检索使用。
 */
@Component
public class KeywordLegalSearcher implements LegalDocumentSearcher {

    private final JdbcTemplate pgJdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String table;
    private final LegalIndexVersionProvider indexVersions;
    private final LegalQueryAnalyzer queryAnalyzer;

    public KeywordLegalSearcher(@Qualifier("pgDataSource") DataSource pgDataSource,
                                RagProperties properties, LegalIndexVersionProvider indexVersions,
                                LegalQueryAnalyzer queryAnalyzer) {
        this.pgJdbc = new JdbcTemplate(pgDataSource);
        if (!properties.getPg().getTable().matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法 PGVector 表名");
        }
        this.table = properties.getPg().getTable();
        this.indexVersions = indexVersions;
        this.queryAnalyzer = queryAnalyzer;
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        return searchInternal(query, topK, null);
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        return searchInternal(request.query(), topK, request);
    }

    private List<LegalDoc> searchInternal(String query, int topK, RetrievalRequest request) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String version = indexVersions.activeVersion();
        if (version.isBlank()) return List.of();
        // 转义 LIKE 通配符（% _ \），避免用户/模型输入的 query 被当作通配符导致召回面异常或膨胀
        List<String> terms = queryAnalyzer.terms(query);
        if (terms.isEmpty()) return List.of();
        String conditions = terms.stream()
                .map(ignored -> "(text ILIKE ? ESCAPE '\\' OR metadata::text ILIKE ? ESCAPE '\\')")
                .collect(java.util.stream.Collectors.joining(" OR "));
        String scoreExpression = terms.stream()
                .map(ignored -> "CASE WHEN text ILIKE ? ESCAPE '\\' OR metadata::text ILIKE ? ESCAPE '\\' THEN 1 ELSE 0 END")
                .collect(java.util.stream.Collectors.joining(" + "));
        StringBuilder sql = new StringBuilder("SELECT text, metadata::text FROM ").append(table)
                .append(" WHERE (").append(conditions).append(")")
                .append(" AND metadata::jsonb ->> 'corpusVersion' = ?");
        List<Object> params = new java.util.ArrayList<>();
        for (String term : terms) {
            String like = "%" + escapeLike(term) + "%";
            params.add(like);
            params.add(like);
        }
        params.add(version);
        if (request != null) {
            sql.append(" AND metadata::jsonb ->> 'jurisdiction' = ? AND (");
            params.add(request.jurisdiction());
            int scopeIndex = 0;
            for (String scope : request.accessScopes()) {
                if (scopeIndex++ > 0) sql.append(" OR ");
                // PostgreSQL 同时把 || 用作 jsonb 运算符；必须先括住 ->> 文本结果，避免把逗号按 JSON 解析。
                sql.append("(',' || (metadata::jsonb ->> 'accessScopes') || ',') LIKE ?");
                params.add("%," + scope + ",%");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY (").append(scoreExpression).append(") DESC, length(text) ASC LIMIT ?");
        // ORDER BY 的每个占位符必须按 SQL 出现顺序再次绑定。
        for (String term : terms) {
            String like = "%" + escapeLike(term) + "%";
            params.add(like);
            params.add(like);
        }
        params.add(topK);
        return pgJdbc.query(sql.toString(), (rs, i) -> toLegalDoc(rs.getString(1), rs.getString(2)), params.toArray());
    }

    /** 转义 PostgreSQL LIKE/ILIKE 的保留通配符 */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private LegalDoc toLegalDoc(String text, String metadataJson) {
        String evidenceId = "", title = "", docNumber = "", article = "";
        LegalEvidenceMetadata evidenceMetadata = LegalEvidenceMetadata.untrustedMetadata();
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            evidenceId = node.path("evidenceId").asText();
            title = node.path("title").asText();
            docNumber = node.path("documentNumber").asText();
            article = node.path("articleNumber").asText();
            java.util.Set<String> scopes = java.util.Arrays.stream(node.path("accessScopes").asText("").split(","))
                    .map(String::trim).filter(v -> !v.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            evidenceMetadata = new LegalEvidenceMetadata(node.path("documentId").asText(),
                    node.path("parentSection").asText(), node.path("jurisdiction").asText(""),
                    date(node.path("effectiveFrom").asText()), date(node.path("effectiveTo").asText()), scopes, node.path("contentDigest").asText(),
                    node.path("corpusVersion").asText(), node.path("sourceFile").asText(),
                    node.path("securityStatus").asText(""));
        } catch (Exception ignored) {
            // 损坏或旧格式元数据保持隔离，不得回退为公开可信。
        }
        return new LegalDoc(evidenceId, title, docNumber, article, text, evidenceMetadata);
    }

    private java.time.LocalDate date(String value) {
        return value == null || value.isBlank() ? null : java.time.LocalDate.parse(value);
    }
}
