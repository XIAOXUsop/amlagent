package com.bank.aml.rag;

import com.bank.aml.config.LegalVectorTable;
import com.bank.aml.config.RagProperties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 法规向量库（PGVector）Flyway 迁移执行器。
 * <p>
 * 替代历史 {@code LegalSearchIndexManager} 的启动动态 DDL：把 pg_trgm / 全文 / 过滤字段 / HNSW 索引 迁入版本化脚本
 * {@code db/pg-migration}，保证索引 schema 与语料迭代同步演进。
 * </p>
 * <p>
 * 业务库（MySQL）仍由 Spring Boot 自带 Flyway 管理；本组件以独立 Flyway 实例作用于 PG 库。
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 80)
public class PgFlywayInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PgFlywayInitializer.class);

    private final DataSource pgDataSource;

    private final LegalVectorTable table;

    public PgFlywayInitializer(@Qualifier("pgDataSource") DataSource pgDataSource, RagProperties properties) {
        this.pgDataSource = pgDataSource;
        this.table = LegalVectorTable.fromConfiguration(properties.getPg().getTable());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (table != LegalVectorTable.PRODUCTION) {
            // 迁移脚本针对默认表 legal_docs 编写；自定义表名（开发/测试库）跳过，避免对不存在表建索引。
            log.info("PG 表名为测试允许项 {}，跳过生产 Flyway 迁移", table.configuredName());
            return;
        }
        try {
            Flyway.configure()
                .dataSource(pgDataSource)
                .locations("classpath:db/pg-migration")
                // 历史 PG 库可能已有 legal_docs 但没有 schema history：以 V0 建基线，
                // 使幂等的 V1 仍会执行并补齐缺失扩展/表，再继续执行后续索引迁移。
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
            log.info("PG 法规向量库索引迁移完成");
        }
        catch (Exception e) {
            throw new IllegalStateException("PG 法规向量库索引迁移失败，拒绝启动", e);
        }
    }

}
