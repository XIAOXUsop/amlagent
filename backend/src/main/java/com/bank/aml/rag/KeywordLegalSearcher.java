package com.bank.aml.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.bank.aml.config.RagProperties;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 PostgreSQL 的字段化中文关键词召回：对 content/documentNumber/articleNumber/title/legalActionCode
 * 分别做 ILIKE 并按字段权重加权，不整表搜 metadata::text（避免日期/哈希/文件名噪声）。
 * <p>权重：documentNumber 5.0 / articleNumber 5.0 / title 3.0 / legalActionCode 2.5 / content 1.0。</p>
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
        return searchScoredInternal(query, topK, null).stream().map(SearchHit::document).toList();
    }

    @Override
    public List<LegalDoc> search(RetrievalRequest request, int topK) {
        return searchScored(request, topK).stream().map(SearchHit::document).toList();
    }

    @Override
    public List<SearchHit> searchScored(RetrievalRequest request, int topK) {
        return searchScoredInternal(request.query(), topK, request);
    }

    private List<SearchHit> searchScoredInternal(String query, int topK, RetrievalRequest request) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        long started = System.nanoTime();
        String version = indexVersions.versionFor(request);
        if (version.isBlank()) return List.of();
        List<String> terms = queryAnalyzer.terms(query);
        if (terms.isEmpty()) return List.of();
        LegalQueryAnalyzer.ParsedQuery parsed = queryAnalyzer.parse(query);

        StringBuilder conditions = new StringBuilder();
        StringBuilder scoreExpression = new StringBuilder();
        List<Object> scoreParams = new ArrayList<>();
        List<Object> conditionParams = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            String like = "%" + escapeLike(terms.get(i)) + "%";
            if (i > 0) conditions.append(" OR ");
            conditions.append("(text ILIKE ? ESCAPE '\\' OR metadata::jsonb ->> 'documentNumber' ILIKE ? ESCAPE '\\'"
                    + " OR metadata::jsonb ->> 'articleNumber' ILIKE ? ESCAPE '\\'"
                    + " OR metadata::jsonb ->> 'title' ILIKE ? ESCAPE '\\'"
                    + " OR metadata::jsonb ->> 'legalActionCode' ILIKE ? ESCAPE '\\')");
            if (i > 0) scoreExpression.append(" + ");
            // 字段加权：文号/条号 5.0、标题 3.0、行为码 2.5、正文 1.0
            scoreExpression.append("(CASE WHEN metadata::jsonb ->> 'documentNumber' ILIKE ? ESCAPE '\\' THEN 5.0 ELSE 0 END")
                    .append(" + CASE WHEN metadata::jsonb ->> 'articleNumber' ILIKE ? ESCAPE '\\' THEN 5.0 ELSE 0 END")
                    .append(" + CASE WHEN metadata::jsonb ->> 'title' ILIKE ? ESCAPE '\\' THEN 3.0 ELSE 0 END")
                    .append(" + CASE WHEN metadata::jsonb ->> 'legalActionCode' ILIKE ? ESCAPE '\\' THEN 2.5 ELSE 0 END")
                    .append(" + CASE WHEN text ILIKE ? ESCAPE '\\' THEN 1.0 ELSE 0 END)");
            for (int p = 0; p < 5; p++) scoreParams.add(like);
            for (int p = 0; p < 5; p++) conditionParams.add(like);
        }
        // 显式文号/条号命中的字段级加分
        List<String> authorityTokens = new ArrayList<>();
        authorityTokens.addAll(parsed.docNumbers());
        authorityTokens.addAll(parsed.articleNumbers());
        for (String token : authorityTokens) {
            if (conditions.length() > 0) conditions.append(" OR ");
            String like = "%" + escapeLike(token) + "%";
            conditions.append("(metadata::jsonb ->> 'documentNumber' ILIKE ? ESCAPE '\\'"
                    + " OR metadata::jsonb ->> 'articleNumber' ILIKE ? ESCAPE '\\')");
            conditionParams.add(like);
            conditionParams.add(like);
        }

        StringBuilder sql = new StringBuilder("SELECT text, metadata::text, (")
                .append(scoreExpression).append(") AS score FROM ").append(table)
                .append(" WHERE (").append(conditions).append(")")
                .append(" AND metadata::jsonb ->> 'corpusVersion' = ?");
        // JDBC 参数必须严格按 SQL 中占位符出现顺序合并：SELECT 得分 → WHERE 条件 → 版本/授权过滤 → LIMIT。
        List<Object> params = new ArrayList<>(scoreParams.size() + conditionParams.size() + 20);
        params.addAll(scoreParams);
        params.addAll(conditionParams);
        params.add(version);
        if (request != null) {
            sql.append(" AND metadata::jsonb ->> 'jurisdiction' = ? AND (");
            params.add(request.jurisdiction());
            int scopeIndex = 0;
            for (String scope : request.accessScopes()) {
                if (scopeIndex++ > 0) sql.append(" OR ");
                sql.append("(',' || (metadata::jsonb ->> 'accessScopes') || ',') LIKE ?");
                params.add("%," + scope + ",%");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY score DESC, length(text) ASC LIMIT ?");
        params.add(topK);

        List<SearchHit> rows = pgJdbc.query(sql.toString(), (rs, i) -> {
            double score = rs.getDouble(3);
            return toSearchHit(rs.getString(1), rs.getString(2), score, terms, parsed);
        }, params.toArray());
        List<SearchHit> ranking = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            SearchHit row = rows.get(i);
            ranking.add(new SearchHit(row.document(), null, null, i + 1, row.lexicalScore(),
                    null, null, null, row.channels(), row.matchReasons()));
        }
        RetrievalTimings.add("lexical", Math.max(0, (System.nanoTime() - started) / 1_000_000));
        return ranking;
    }

    /** 转义 PostgreSQL LIKE/ILIKE 的保留通配符 */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private SearchHit toSearchHit(String text, String metadataJson, double score, List<String> terms,
                                  LegalQueryAnalyzer.ParsedQuery parsed) {
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
                    date(node.path("effectiveFrom").asText()), date(node.path("effectiveTo").asText()), scopes,
                    node.path("contentDigest").asText(), node.path("corpusVersion").asText(),
                    node.path("sourceFile").asText(), node.path("securityStatus").asText(""));
        } catch (Exception ignored) {
            // 损坏或旧格式元数据保持隔离，不得回退为公开可信。
        }
        List<String> reasons = new ArrayList<>();
        for (String term : terms) {
            if (contains(text, term)) reasons.add("命中内容词:" + term);
        }
        for (String regulation : parsed.regulations()) {
            if (contains(title, regulation)) reasons.add("标题含法规:" + regulation);
        }
        String documentKey = docNumber + "|" + article;
        for (String docNo : parsed.docNumbers()) {
            if (contains(docNumber, docNo) || documentKey.contains(docNo)) reasons.add("命中文号:" + docNo);
        }
        for (String articleNo : parsed.articleNumbers()) {
            if (article.contains(articleNo) || articleNo.contains(article)) reasons.add("命中条号:" + articleNo);
        }
        if (reasons.isEmpty()) reasons.add("关键词加权召回");
        LegalDoc doc = new LegalDoc(evidenceId, title, docNumber, article, text, evidenceMetadata);
        return new SearchHit(doc, null, null, null, score, null, null, null,
                java.util.Set.of(RetrievalChannel.LEXICAL), reasons);
    }

    private boolean contains(String value, String token) {
        return value != null && token != null && value.contains(token);
    }

    private java.time.LocalDate date(String value) {
        return value == null || value.isBlank() ? null : java.time.LocalDate.parse(value);
    }
}
