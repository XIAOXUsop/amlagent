package com.bank.aml.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * 基于 PostgreSQL 的关键词/全文召回（ILIKE 精确匹配），供混合检索使用。
 */
@Component
public class KeywordLegalSearcher implements LegalDocumentSearcher {

    private final JdbcTemplate pgJdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KeywordLegalSearcher(@Qualifier("pgDataSource") DataSource pgDataSource) {
        this.pgJdbc = new JdbcTemplate(pgDataSource);
    }

    @Override
    public List<LegalDoc> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // 转义 LIKE 通配符（% _ \），避免用户/模型输入的 query 被当作通配符导致召回面异常或膨胀
        String like = "%" + escapeLike(query.trim()) + "%";
        String sql = """
                SELECT text, metadata::text FROM legal_docs
                WHERE text ILIKE ? ESCAPE '\\' OR metadata::text ILIKE ? ESCAPE '\\'
                ORDER BY length(text) ASC
                LIMIT ?
                """;
        return pgJdbc.query(sql, (rs, i) -> toLegalDoc(rs.getString(1), rs.getString(2)), like, like, topK);
    }

    /** 转义 PostgreSQL LIKE/ILIKE 的保留通配符 */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private LegalDoc toLegalDoc(String text, String metadataJson) {
        String evidenceId = "", title = "", docNumber = "", article = "";
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            evidenceId = node.path("evidenceId").asText();
            title = node.path("title").asText();
            docNumber = node.path("documentNumber").asText();
            article = node.path("articleNumber").asText();
        } catch (Exception ignored) {
            // metadata 解析失败时使用空值
        }
        return new LegalDoc(evidenceId, title, docNumber, article, text);
    }
}
