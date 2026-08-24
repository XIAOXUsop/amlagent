package com.bank.aml.rag.ingestion;

import com.bank.aml.config.RagProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** 候选版本写入前清除同版本残片，使崩溃重试不会累积重复 chunk。 */
@Component
public class LegalCandidateIndexStore {
    private final JdbcTemplate jdbc;
    private final String table;

    public LegalCandidateIndexStore(@Qualifier("pgDataSource") DataSource dataSource, RagProperties properties) {
        if (!properties.getPg().getTable().matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("非法 PGVector 表名");
        this.jdbc = new JdbcTemplate(dataSource);
        this.table = properties.getPg().getTable();
    }

    public int clearCandidate(String version) {
        if (version == null || !version.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("非法候选索引版本");
        return jdbc.update("DELETE FROM " + table + " WHERE metadata::jsonb ->> 'corpusVersion' = ?", version);
    }
}
