package com.bank.aml.rag;

import com.bank.aml.config.RagProperties;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 法规向量库（PGVector）Flyway 迁移执行器。
 * <p>替代历史 {@code LegalSearchIndexManager} 的启动动态 DDL：把 pg_trgm / 全文 / 过滤字段 / HNSW 索引
 * 迁入版本化脚本 {@code db/pg-migration}，保证索引 schema 与语料迭代同步演进。</p>
 * <p>业务库（MySQL）仍由 Spring Boot 自带 Flyway 管理；本组件以独立 Flyway 实例作用于 PG 库。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 80)
public class PgFlywayInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PgFlywayInitializer.class);
    private static final String DEFAULT_TABLE = "legal_docs";

    private final DataSource pgDataSource;
    private final String table;

    public PgFlywayInitializer(@Qualifier("pgDataSource") DataSource pgDataSource, RagProperties properties) {
        if (!properties.getPg().getTable().matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法 PGVector 表名");
        }
        this.pgDataSource = pgDataSource;
        this.table = properties.getPg().getTable();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!DEFAULT_TABLE.equals(table)) {
            // 迁移脚本针对默认表 legal_docs 编写；自定义表名（开发/测试库）跳过，避免对不存在表建索引。
            log.info("PG 表名非默认 {}（当前 {}），跳过 Flyway 索引迁移", DEFAULT_TABLE, table);
            return;
        }
        try {
            Flyway.configure()
                    .dataSource(pgDataSource)
                    .locations("classpath:db/pg-migration")
                    // 历史 PG 库已经存在 legal_docs 且没有 schema history：先将其标记为 V1，
                    // 再执行 V2 索引迁移，避免 baselineOnMigrate 默认把同名 V1 直接跳过。
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .load()
                    .migrate();
            log.info("PG 法规值库索引迁移完成");
        } catch (Exception e) {
            log.error("PG 法规向量库索引迁移失败：{}", e.getMessage());
            throw new IllegalStateException("PG 法规向量库索引迁移失败，拒绝启动", e);
        }
    }
}
