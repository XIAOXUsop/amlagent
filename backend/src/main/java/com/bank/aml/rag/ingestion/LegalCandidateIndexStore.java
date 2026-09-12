package com.bank.aml.rag.ingestion;

import com.bank.aml.config.LegalVectorTable;
import com.bank.aml.config.RagProperties;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 候选版本写入前清除同版本残片，使崩溃重试不会累积重复 chunk。 */
@Component
public class LegalCandidateIndexStore {

    private final JdbcTemplate jdbc;

    private final LegalVectorTable table;

    public LegalCandidateIndexStore(@Qualifier("pgDataSource") DataSource dataSource, RagProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.table = LegalVectorTable.fromConfiguration(properties.getPg().getTable());
    }

    public int clearCandidate(String version) {
        if (version == null || !version.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("非法候选索引版本");
        return jdbc.update(table.deleteByVersionSql(), version);
    }

    /** 候选索引落库条数，用于发布门禁的「索引完整性」校验。 */
    public int candidateCount(String version) {
        if (version == null || !version.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("非法候选索引版本");
        Integer count = jdbc.queryForObject(table.countByVersionSql(), Integer.class, version);
        return count == null ? 0 : count;
    }

}
