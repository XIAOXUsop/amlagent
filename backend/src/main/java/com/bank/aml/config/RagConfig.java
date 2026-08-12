package com.bank.aml.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * RAG 基础设施：PostgreSQL(pgvector) 数据源、本地 embedding 模型、向量存储。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

    /** 独立的 PostgreSQL(pgvector) 数据源（区别于 MySQL 业务主数据源） */
    @Bean
    public DataSource pgDataSource(RagProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.getPg().getUrl());
        ds.setUsername(props.getPg().getUsername());
        ds.setPassword(props.getPg().getPassword());
        ds.setMaximumPoolSize(5);
        return ds;
    }

    /** 本地 embedding 模型：all-MiniLM-L6-v2，384 维，离线可用 */
    @Bean
    public EmbeddingModel embeddingModel() {
        return new AllMiniLmL6V2EmbeddingModel();
    }

    /** PGVector 向量存储（存储法规条文与案例库） */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(RagProperties props,
                                                      @Qualifier("pgDataSource") DataSource pgDataSource) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(pgDataSource)
                .table(props.getPg().getTable())
                .dimension(props.getPg().getDimensions())
                .createTable(true)
                .build();
    }
}
