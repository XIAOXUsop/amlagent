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
        String like = "%" + query.trim() + "%";
        String sql = """
                SELECT text, metadata::text FROM legal_docs
                WHERE text ILIKE ? OR metadata::text ILIKE ?
                ORDER BY length(text) ASC
                LIMIT ?
                """;
        return pgJdbc.query(sql, (rs, i) -> toLegalDoc(rs.getString(1), rs.getString(2)), like, like, topK);
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
