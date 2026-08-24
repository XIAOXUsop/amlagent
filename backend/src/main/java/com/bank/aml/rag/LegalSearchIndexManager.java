package com.bank.aml.rag;

import com.bank.aml.config.RagProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** 为法规词法召回建立 PostgreSQL trigram/全文索引；索引创建幂等。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class LegalSearchIndexManager implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final String table;

    public LegalSearchIndexManager(@Qualifier("pgDataSource") DataSource dataSource, RagProperties properties,
                                   dev.langchain4j.store.embedding.EmbeddingStore<?> ignoredStore) {
        if (!properties.getPg().getTable().matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("非法表名");
        this.jdbc = new JdbcTemplate(dataSource);
        this.table = properties.getPg().getTable();
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbc.execute("CREATE INDEX IF NOT EXISTS " + table + "_text_trgm_idx ON " + table
                + " USING gin (text gin_trgm_ops)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS " + table + "_fts_idx ON " + table
                + " USING gin (to_tsvector('simple', coalesce(text, '')))");
    }
}
